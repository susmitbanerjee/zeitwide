package com.settlecal.api.ratelimits.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlecal.api.auth.app.ApiKeyAuthenticator;
import com.settlecal.api.auth.domain.Caller;
import com.settlecal.api.ratelimits.app.RateLimits;
import com.settlecal.api.ratelimits.domain.Allowance;
import com.settlecal.api.web.ProblemCode;
import com.settlecal.api.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every /v1 request: who is calling (an API key, or an anonymous address), then their limit. Runs
 * before Spring MVC, so it writes its problem details itself.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

  static final String LIMIT = "RateLimit-Limit";
  static final String REMAINING = "RateLimit-Remaining";
  private static final String API_PREFIX = "/v1/";
  private static final String BEARER = "Bearer ";
  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private final ApiKeyAuthenticator authenticator;
  private final RateLimits rateLimits;
  private final ObjectMapper json;

  RateLimitFilter(ApiKeyAuthenticator authenticator, RateLimits rateLimits, ObjectMapper json) {
    this.authenticator = authenticator;
    this.rateLimits = rateLimits;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<Caller> caller = caller(request);
    if (caller.isEmpty()) {
      write(
          response,
          Problems.of(
              ProblemCode.INVALID_API_KEY, "Send a valid key as Authorization: Bearer sc_..."));
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

  /** Empty when an Authorization header is present but holds no valid key. */
  private Optional<Caller> caller(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null) {
      String address =
          ClientAddress.of(
              request.getRemoteAddr(),
              request.getHeader(FORWARDED_FOR),
              rateLimits.trustedProxies());
      return Optional.of(new Caller.Anonymous(address));
    }
    if (!authorization.startsWith(BEARER)) {
      return Optional.empty();
    }
    return authenticator.authenticate(authorization.substring(BEARER.length()).trim()).map(k -> k);
  }

  private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
