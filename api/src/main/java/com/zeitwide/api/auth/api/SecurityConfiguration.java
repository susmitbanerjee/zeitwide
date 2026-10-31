package com.zeitwide.api.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zeitwide.api.auth.app.AccountJwtAuthenticationConverter;
import com.zeitwide.api.auth.app.ApiKeyAuthenticator;
import com.zeitwide.api.auth.app.AuthProperties;
import com.zeitwide.api.auth.app.InternalAuthProperties;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.mcp.app.McpProperties;
import com.zeitwide.api.web.CorsProperties;
import com.zeitwide.api.web.ProblemCode;
import com.zeitwide.api.web.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  AuthProperties.class,
  InternalAuthProperties.class,
  CorsProperties.class
})
public class SecurityConfiguration {

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<
          com.zeitwide.api.ratelimits.api.RateLimitFilter>
      rateLimitFilterRegistration(com.zeitwide.api.ratelimits.api.RateLimitFilter rateLimitFilter) {
    var registration = new FilterRegistrationBean<>(rateLimitFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  JwtDecoder keycloakJwtDecoder(AuthProperties properties) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
            .cache(jwksCache("zeitwide-keycloak-jwks"))
            .build();
    decoder.setJwtValidator(keycloakTokenValidator(properties.issuerUri(), properties.audience()));
    return decoder;
  }

  @Bean
  JwtDecoder googleSchedulerJwtDecoder(InternalAuthProperties properties) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.googleJwkSetUri())
            .cache(jwksCache("zeitwide-google-jwks"))
            .build();
    decoder.setJwtValidator(
        new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("https://accounts.google.com")));
    return decoder;
  }

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      AccountJwtAuthenticationConverter jwtConverter,
      ApiKeyAuthenticator apiKeys,
      ObjectMapper json,
      @Qualifier("keycloakJwtDecoder") JwtDecoder keycloakJwtDecoder,
      @Qualifier("googleSchedulerJwtDecoder") JwtDecoder googleJwtDecoder,
      InternalAuthProperties internalProperties,
      McpProperties mcpProperties,
      com.zeitwide.api.ratelimits.api.RateLimitFilter rateLimitFilter,
      BearerTokenResolver resolver)
      throws Exception {
    AuthenticationEntryPoint authenticationEntryPoint =
        (request, response, exception) -> {
          String path = request.getRequestURI().substring(request.getContextPath().length());
          if (path.equals("/mcp")) {
            response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\""
                    + mcpProperties.protectedResourceMetadataUrl()
                    + "\"");
          }
          writeProblem(response, json, ProblemCode.UNAUTHORIZED, "A valid identity is required");
        };
    AccessDeniedHandler accessDeniedHandler =
        (request, response, exception) ->
            writeProblem(
                response, json, ProblemCode.FORBIDDEN, "The caller lacks the required role");
    var internalFilter =
        new InternalSchedulerTokenFilter(
            googleJwtDecoder,
            internalProperties.audience(),
            internalProperties.serviceAccountEmail(),
            json);
    var apiKeyFilter = new ApiKeyAuthenticationFilter(apiKeys, json, mcpProperties);

    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .headers(
            headers ->
                headers
                    .frameOptions(frame -> frame.deny())
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/v1/calendars/**", "/v1/business-days/**")
                    .permitAll()
                    .requestMatchers("/v1/changes", "/v1/receipts/verify")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/.well-known/zeitwide-keys.json",
                        "/.well-known/oauth-protected-resource/mcp",
                        "/docs",
                        "/docs/**",
                        "/openapi.yaml",
                        "/v3/api-docs/**",
                        "/swagger-ui/**")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/v1/account/**")
                    .authenticated()
                    .requestMatchers("/mcp")
                    .authenticated()
                    .requestMatchers("/admin/**")
                    .hasRole("reviewer")
                    .requestMatchers("/internal/**")
                    .hasRole("SCHEDULER")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .bearerTokenResolver(resolver)
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .jwt(
                        jwt ->
                            jwt.decoder(keycloakJwtDecoder)
                                .jwtAuthenticationConverter(jwtConverter)))
        .addFilterBefore(internalFilter, BearerTokenAuthenticationFilter.class)
        .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class)
        .addFilterAfter(rateLimitFilter, AnonymousAuthenticationFilter.class)
        .build();
  }

  @Bean
  BearerTokenResolver bearerTokenResolver() {
    DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
    return request -> {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      String authorization = request.getHeader("Authorization");
      if (path.equals("/internal")
          || path.startsWith("/internal/")
          || (authorization != null && authorization.startsWith("Bearer " + ApiKeyToken.SCHEME))) {
        return null;
      }
      return delegate.resolve(request);
    };
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            "MCP-Protocol-Version",
            "MCP-Session-Id",
            "Last-Event-ID"));
    configuration.setExposedHeaders(
        List.of(
            "RateLimit-Limit",
            "RateLimit-Remaining",
            "Retry-After",
            "MCP-Protocol-Version",
            "MCP-Session-Id"));
    configuration.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  static OAuth2TokenValidator<Jwt> keycloakTokenValidator(String issuer, String audience) {
    return new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audience));
  }

  private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
    return jwt ->
        jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new org.springframework.security.oauth2.core.OAuth2Error(
                    "invalid_token", "Required audience is missing", null));
  }

  private static CaffeineCache jwksCache(String name) {
    return new CaffeineCache(
        name,
        Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(10)).build());
  }

  private static void writeProblem(
      HttpServletResponse response, ObjectMapper json, ProblemCode code, String detail)
      throws java.io.IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), Problems.of(code, detail));
  }
}
