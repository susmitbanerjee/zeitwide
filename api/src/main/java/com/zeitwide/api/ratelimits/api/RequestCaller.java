package com.zeitwide.api.ratelimits.api;

import com.zeitwide.api.auth.domain.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * The caller {@link RateLimitFilter} resolved for a request, for the agent endpoints (MCP and A2A)
 * that answer anonymous callers differently: no receipt, and a per-network call limit.
 */
public final class RequestCaller {

  private static final String ATTRIBUTE = RequestCaller.class.getName();

  private RequestCaller() {}

  static void set(HttpServletRequest request, Caller caller) {
    request.setAttribute(ATTRIBUTE, caller);
  }

  /** Empty only when the request never passed the rate limit filter. */
  public static Optional<Caller> of(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof Caller caller
        ? Optional.of(caller)
        : Optional.empty();
  }
}
