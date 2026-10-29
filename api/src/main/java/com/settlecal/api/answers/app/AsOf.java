package com.settlecal.api.answers.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** When a question is asked as of: now, unless the caller names an instant. */
public sealed interface AsOf {

  Instant resolve(Clock clock);

  static AsOf now() {
    return new Now();
  }

  static AsOf at(Instant instant) {
    return new At(instant);
  }

  record Now() implements AsOf {
    @Override
    public Instant resolve(Clock clock) {
      return clock.instant();
    }
  }

  record At(Instant instant) implements AsOf {
    public At {
      Objects.requireNonNull(instant, "instant");
    }

    @Override
    public Instant resolve(Clock clock) {
      return instant;
    }
  }
}
