package com.zeitwide.api.ratelimits.domain;

import java.time.Duration;
import java.util.Objects;

/** {@code capacity} requests per {@code period}, refilled smoothly. */
public record Limit(long capacity, Duration period) {

  public Limit {
    Objects.requireNonNull(period, "period");
    if (capacity <= 0 || period.isNegative() || period.isZero()) {
      throw new IllegalArgumentException("A limit needs a positive capacity and period");
    }
  }
}
