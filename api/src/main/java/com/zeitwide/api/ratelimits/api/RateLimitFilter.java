package com.zeitwide.api.ratelimits.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.auth.app.ApiKeyAuthenticator;
import com.zeitwide.api.auth.domain.AccountPrincipal;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.auth.domain.Caller;
import com.zeitwide.api.ratelimits.app.RateLimits;
import com.zeitwide.api.ratelimits.domain.Allowance;
import com.zeitwide.api.web.ProblemCode;
import com.zeitwide.api.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Public answer routes are rate limited after authentication, using the resolved account identity
 * where one is available and the client address for anonymous callers.
 */
@Component
public final class RateLimitFilter extends OncePerRequestFilter {

  static final String LIMIT = "RateLimit-Limit";
  static final String REMAINING = "RateLimit-Remaining";
  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private final RateLimits rateLimits;
  private final ApiKeyAuthenticator authenticator;
  private final ObjectMapper json;

  public RateLimitFilter(
      RateLimits rateLimits, ApiKeyAuthenticator authenticator, ObjectMapper json) {
    this.rateLimits = rateLimits;
    this.authenticator = authenticator;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.startsWith("/v1/") || path.equals("/mcp"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<Caller> caller = caller(request);
    if (caller.isEmpty()) {
      write(response, Problems.of(ProblemCode.INVALID_API_KEY, "The API key is not valid"));
      return;
    }
    Allowance allowance = rateLimits.consume(caller.get());
    response.setHeader(LIMIT, Long.toString(allowance.limit()));
    switch (allowance) {
      case Allowance.Allowed allowed -> {
        response.setHeader(REMAINING, Long.toString(allowed.remaining()));
        chain.doFilter(request, response);
      }
      case Allowance.Denied denied -> {
        long seconds = Math.max(1, (long) Math.ceil(denied.retryAfter().toMillis() / 1000.0));
        response.setHeader(REMAINING, "0");
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        write(
            response,
            Problems.of(ProblemCode.RATE_LIMITED, "Try again in " + seconds + " seconds"));
      }
    }
  }

  private Optional<Caller> caller(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AccountPrincipal principal) {
      if (principal.apiKeyId() != null) {
        return Optional.of(new Caller.Keyed(principal.apiKeyId(), principal.accountId()));
      }
      return Optional.of(new Caller.Authenticated(principal.accountId()));
    }
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null) {
      if (!authorization.startsWith("Bearer " + ApiKeyToken.SCHEME)) {
        return Optional.empty();
      }
      return authenticator
          .authenticate(authorization.substring("Bearer ".length()).trim())
          .map(key -> new Caller.Keyed(key.apiKeyId(), key.accountId()));
    }
    String address =
        ClientAddress.of(
            request.getRemoteAddr(), request.getHeader(FORWARDED_FOR), rateLimits.trustedProxies());
    return Optional.of(new Caller.Anonymous(address));
  }

  private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
