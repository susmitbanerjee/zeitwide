package com.zeitwide.api.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.web.ProblemCode;
import com.zeitwide.api.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Verifies Cloud Scheduler's Google-issued OIDC token on every internal route. */
public final class InternalSchedulerTokenFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";
  private final JwtDecoder decoder;
  private final String audience;
  private final String serviceAccountEmail;
  private final ObjectMapper json;

  public InternalSchedulerTokenFilter(
      JwtDecoder decoder, String audience, String serviceAccountEmail, ObjectMapper json) {
    this.decoder = decoder;
    this.audience = audience;
    this.serviceAccountEmail = serviceAccountEmail;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.equals("/internal") || path.startsWith("/internal/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    Jwt jwt = null;
    if (authorization != null && authorization.startsWith(PREFIX)) {
      try {
        jwt = decoder.decode(authorization.substring(PREFIX.length()).trim());
      } catch (JwtException | IllegalArgumentException ignored) {
        // A rejected credential never reaches the application or appears in a log.
      }
    }
    if (jwt == null || !isScheduler(jwt)) {
      var problem = Problems.of(ProblemCode.UNAUTHORIZED, "A valid scheduler identity is required");
      response.setStatus(problem.getStatus());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      json.writeValue(response.getOutputStream(), problem);
      return;
    }
    var authentication =
        new UsernamePasswordAuthenticationToken(
            serviceAccountEmail, null, List.of(new SimpleGrantedAuthority("ROLE_SCHEDULER")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    chain.doFilter(request, response);
  }

  private boolean isScheduler(Jwt jwt) {
    return "https://accounts.google.com"
            .equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
        && jwt.getAudience().contains(audience)
        && serviceAccountEmail.equals(jwt.getClaimAsString("email"))
        && Boolean.TRUE.equals(jwt.getClaim("email_verified"));
  }
}
