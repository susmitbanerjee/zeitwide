package com.zeitwide.api.auth.app;

import org.springframework.stereotype.Service;

/** Central feature gate. The MVP currently allows every feature for every account. */
@Service
public class Entitlements {

  public void check(Feature feature) {
    // Deliberately allow all plans in the MVP; feature checks remain centralized here.
    java.util.Objects.requireNonNull(feature, "feature");
  }

  public enum Feature {
    ANSWERS,
    ACCOUNT,
    API_KEYS,
    WEBHOOKS,
    ALERTS,
    ADMIN
  }
}
