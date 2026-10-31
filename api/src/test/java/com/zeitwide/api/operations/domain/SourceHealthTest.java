package com.zeitwide.api.operations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SourceHealthTest {

  private static final Instant CHECKED = Instant.parse("2026-10-31T06:00:00Z");

  @Test
  void shouldRankDisabledThenSuspendedThenNeverCheckedThenFailures() {
    assertThat(health(false, 0, CHECKED, null).status()).isEqualTo(SourceHealth.Status.DISABLED);
    assertThat(health(true, 3, CHECKED, CHECKED).status()).isEqualTo(SourceHealth.Status.SUSPENDED);
    assertThat(health(true, 0, null, null).status()).isEqualTo(SourceHealth.Status.NEVER_CHECKED);
    assertThat(health(true, 1, CHECKED, null).status()).isEqualTo(SourceHealth.Status.FAILING);
    assertThat(health(true, 0, CHECKED, null).status()).isEqualTo(SourceHealth.Status.HEALTHY);
    assertThat(SourceHealth.Status.NEVER_CHECKED.code()).isEqualTo("never_checked");
  }

  private static SourceHealth health(
      boolean enabled, int failures, Instant checked, Instant suspended) {
    return new SourceHealth(
        "gov-uk",
        "GOV.UK",
        "https://www.gov.uk/bank-holidays",
        "primary",
        "daily",
        enabled,
        failures,
        checked,
        checked,
        checked,
        null,
        null,
        suspended);
  }
}
