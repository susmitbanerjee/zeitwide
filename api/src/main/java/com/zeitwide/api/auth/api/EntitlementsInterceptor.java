package com.zeitwide.api.auth.api;

import com.zeitwide.api.auth.app.Entitlements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Routes every customer feature through the central entitlement check. */
public final class EntitlementsInterceptor implements HandlerInterceptor {

  private final Entitlements entitlements;

  public EntitlementsInterceptor(Entitlements entitlements) {
    this.entitlements = entitlements;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    Entitlements.Feature feature =
        path.startsWith("/admin/")
            ? Entitlements.Feature.ADMIN
            : path.startsWith("/v1/account/")
                ? Entitlements.Feature.ACCOUNT
                : Entitlements.Feature.ANSWERS;
    entitlements.check(feature);
    return true;
  }
}
