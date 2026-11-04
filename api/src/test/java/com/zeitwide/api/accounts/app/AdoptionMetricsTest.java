package com.zeitwide.api.accounts.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.accounts.domain.AdoptionCounts;
import com.zeitwide.api.accounts.domain.SignupSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdoptionMetricsTest {

  private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

  private final List<Instant> queries = new ArrayList<>();
  private final MutableClock clock = new MutableClock(NOW);
  private final MeterRegistry meters = new SimpleMeterRegistry();

  @Test
  void shouldReportCountsAndAskForKeysUsedInTheLast30Days() {
    new AdoptionMetrics(this::counts, clock).bindTo(meters);

    assertThat(gauge("zeitwide.adoption.accounts")).isEqualTo(3);
    assertThat(gauge("zeitwide.adoption.active_keys")).isEqualTo(5);
    assertThat(gauge("zeitwide.adoption.keys_used_30d")).isEqualTo(2);
    assertThat(queries).containsOnly(NOW.minus(Duration.ofDays(30)));
  }

  @Test
  void shouldReportAiReferredAccountsPerVendorIncludingThoseWithNone() {
    new AdoptionMetrics(this::counts, clock).bindTo(meters);

    assertThat(vendorGauge("anthropic")).isEqualTo(1);
    assertThat(vendorGauge("openai")).isZero();
    assertThat(meters.find("zeitwide.adoption.ai_referred_accounts").gauges())
        .hasSize(SignupSource.values().length);
  }

  @Test
  void shouldQueryAtMostOncePerRefreshInterval() {
    new AdoptionMetrics(this::counts, clock).bindTo(meters);

    gauge("zeitwide.adoption.accounts");
    gauge("zeitwide.adoption.active_keys");
    clock.now = NOW.plus(AdoptionMetrics.REFRESH_INTERVAL);
    gauge("zeitwide.adoption.accounts");

    assertThat(queries).hasSize(2);
  }

  private AdoptionCounts counts(Instant usedSince) {
    queries.add(usedSince);
    return new AdoptionCounts(3, 5, 2, Map.of(SignupSource.ANTHROPIC, 1L));
  }

  private double gauge(String name) {
    return meters.get(name).gauge().value();
  }

  private double vendorGauge(String vendor) {
    return meters
        .get("zeitwide.adoption.ai_referred_accounts")
        .tag("vendor", vendor)
        .gauge()
        .value();
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
