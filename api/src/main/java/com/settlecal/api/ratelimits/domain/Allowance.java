package com.settlecal.api.ratelimits.domain;

import java.time.Duration;
import java.util.Objects;

/** Whether a request may go ahead under its limit. */
public sealed interface Allowance {

  long limit();

  record Allowed(long limit, long remaining) implements Allowance {}

  record Denied(long limit, Duration retryAfter) implements Allowance {
    public Denied {
      Objects.requireNonNull(retryAfter, "retryAfter");
    }
  }
}
