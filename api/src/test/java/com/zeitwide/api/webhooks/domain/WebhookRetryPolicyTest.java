package com.zeitwide.api.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookRetryPolicyTest {

  private static final Instant CREATED = Instant.parse("2026-10-29T10:00:00Z");

  @Test
  void appliesExponentialBackoffAndCapsItAtOneHour() {
    assertThat(WebhookRetryPolicy.afterFailure(CREATED, CREATED, 1).nextAttemptAt())
        .isEqualTo(CREATED.plusSeconds(10));
    assertThat(WebhookRetryPolicy.afterFailure(CREATED, CREATED, 2).nextAttemptAt())
        .isEqualTo(CREATED.plusSeconds(20));
    assertThat(WebhookRetryPolicy.afterFailure(CREATED, CREATED, 20).nextAttemptAt())
        .isEqualTo(CREATED.plusSeconds(3600));
  }

  @Test
  void failsAfterTheTwentyFourHourDeliveryWindow() {
    var decision =
        WebhookRetryPolicy.afterFailure(
            CREATED, CREATED.plus(WebhookRetryPolicy.DELIVERY_WINDOW), 5);

    assertThat(decision.failed()).isTrue();
    assertThat(decision.nextAttemptAt()).isNull();
  }
}
