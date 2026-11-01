package com.zeitwide.api.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.auth.app.ApiKeyAuthenticator;
import com.zeitwide.api.auth.app.InvalidKeyThrottle;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.auth.domain.Caller;
import com.zeitwide.api.mcp.app.McpProperties;
import com.zeitwide.api.ratelimits.api.ClientAddress;
import com.zeitwide.api.ratelimits.app.RateLimits;
import com.zeitwide.api.web.ProblemCode;
import com.zeitwide.api.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Recognizes the API-key form of Bearer authentication before OAuth JWT processing. */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
  private static final String BEARER = "Bearer ";
  private final ApiKeyAuthenticator authenticator;
  private final ObjectMapper json;
  private final McpProperties mcpProperties;
  private final InvalidKeyThrottle throttle;
  private final RateLimits rateLimits;

  public ApiKeyAuthenticationFilter(
      ApiKeyAuthenticator authenticator,
      ObjectMapper json,
      McpProperties mcpProperties,
      InvalidKeyThrottle throttle,
      RateLimits rateLimits) {
    this.authenticator = authenticator;
    this.json = json;
    this.mcpProperties = mcpProperties;
    this.throttle = throttle;
    this.rateLimits = rateLimits;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith(BEARER)) {
      chain.doFilter(request, response);
      return;
    }
    String token = authorization.substring(BEARER.length()).trim();
    if (!token.startsWith(ApiKeyToken.SCHEME)) {
      chain.doFilter(request, response);
      return;
    }
    String address =
        ClientAddress.of(
            request.getRemoteAddr(),
            request.getHeader("X-Forwarded-For"),
            rateLimits.trustedProxies());
    if (throttle.isBlocked(address)) {
      response.setHeader(
          HttpHeaders.RETRY_AFTER, Long.toString(InvalidKeyThrottle.retryAfterSeconds()));
      writeProblem(
          response, Problems.of(ProblemCode.RATE_LIMITED, "Too many invalid API keys; wait"));
      return;
    }
    Optional<Caller.Keyed> authenticated;
    try {
      authenticated = authenticator.authenticate(token);
    } catch (DataAccessException unavailable) {
      // A database hiccup is our fault, not a bad key: never answer it with a 401.
      log.atError().setCause(unavailable).log("API key check failed");
      writeProblem(response, Problems.of(ProblemCode.INTERNAL_ERROR, "Try again shortly"));
      return;
    }
    if (authenticated.isEmpty()) {
      throttle.recordFailure(address);
      String path = request.getRequestURI().substring(request.getContextPath().length());
      if (path.equals("/mcp")) {
        response.setHeader(
            HttpHeaders.WWW_AUTHENTICATE,
            "Bearer resource_metadata=\"" + mcpProperties.protectedResourceMetadataUrl() + "\"");
      }
      writeProblem(response, Problems.of(ProblemCode.INVALID_API_KEY, "The API key is not valid"));
      return;
    }
    var key = authenticated.get();
    var principal =
        new com.zeitwide.api.auth.domain.AccountPrincipal(key.accountId(), null, key.apiKeyId());
    var authentication = new AccountAuthenticationToken(principal, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    chain.doFilter(request, response);
  }

  private void writeProblem(HttpServletResponse response, ProblemDetail problem)
      throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
