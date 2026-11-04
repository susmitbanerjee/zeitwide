package com.zeitwide.api.accounts.app;

import com.zeitwide.api.accounts.domain.AdoptionCounts;
import com.zeitwide.api.accounts.domain.SignupSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Account and API key totals as gauges, for the adoption dashboard; sign-ups per day are the
 * account gauge's daily increase. AI-referred accounts get one gauge per known vendor (a closed
 * set, so the label can't grow), labelled with the vendor only. The counts are read at most every
 * {@link #REFRESH_INTERVAL}, so exporting every minute costs three small queries every few minutes.
 */
@Component
public class AdoptionMetrics implements MeterBinder {

  static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
  static final Duration KEY_USE_WINDOW = Duration.ofDays(30);

  private final AdoptionRepository repository;
  private final Clock clock;
  private AdoptionCounts counts;
  private Instant readAt;

  AdoptionMetrics(AdoptionRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    gauge(registry, "zeitwide.adoption.accounts", "Accounts", AdoptionCounts::accounts);
    gauge(
        registry,
        "zeitwide.adoption.active_keys",
        "API keys not revoked",
        AdoptionCounts::activeKeys);
    gauge(
        registry,
        "zeitwide.adoption.keys_used_30d",
        "API keys that called the API in the last 30 days",
        AdoptionCounts::keysUsedLast30Days);
    for (SignupSource source : SignupSource.values()) {
      Gauge.builder(
              "zeitwide.adoption.ai_referred_accounts",
              this,
              metrics -> metrics.current().accountsFrom(source))
          .description("Accounts that signed up after a visit an AI assistant referred")
          .tag("vendor", source.vendor())
          .register(registry);
    }
  }

  private void gauge(
      MeterRegistry registry,
      String name,
      String description,
      ToDoubleFunction<AdoptionCounts> value) {
    Gauge.builder(name, this, metrics -> value.applyAsDouble(metrics.current()))
        .description(description)
        .register(registry);
  }

  synchronized AdoptionCounts current() {
    Instant now = clock.instant();
    if (counts == null || !now.isBefore(readAt.plus(REFRESH_INTERVAL))) {
      counts = repository.counts(now.minus(KEY_USE_WINDOW));
      readAt = now;
    }
    return counts;
  }
}
