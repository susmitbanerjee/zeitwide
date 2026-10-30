package com.zeitwide.api.webhooks.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Exponential retry schedule, bounded by a 24-hour delivery window. */
public final class WebhookRetryPolicy {

  public static final Duration DELIVERY_WINDOW = Duration.ofHours(24);
  private static final Duration INITIAL_DELAY = Duration.ofSeconds(10);
  private static final Duration MAX_DELAY = Duration.ofHours(1);

  private WebhookRetryPolicy() {}

  public static Decision afterFailure(Instant createdAt, Instant now, int attempts) {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(now, "now");
    if (attempts < 1) {
      throw new IllegalArgumentException("At least one delivery attempt is required");
    }
    Instant expiresAt = createdAt.plus(DELIVERY_WINDOW);
    if (!now.isBefore(expiresAt)) {
      return new Decision(true, null);
    }
    long multiplier = 1L << Math.min(attempts - 1, 20);
    Duration delay = INITIAL_DELAY.multipliedBy(multiplier);
    if (delay.compareTo(MAX_DELAY) > 0) {
      delay = MAX_DELAY;
    }
    Instant nextAttempt = now.plus(delay);
    return new Decision(false, nextAttempt.isAfter(expiresAt) ? expiresAt : nextAttempt);
  }

  public record Decision(boolean failed, Instant nextAttemptAt) {}
}
