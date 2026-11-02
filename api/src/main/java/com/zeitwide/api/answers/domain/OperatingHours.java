package com.zeitwide.api.answers.domain;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * When a calendar settles on a business day, in its own time zone, as its operator publishes it.
 * Shown to people only: the engine never reads hours, because entries already say which days are
 * open.
 */
public sealed interface OperatingHours {

  OperatingHours UNPUBLISHED = new Unpublished();

  /** The operator publishes no hours, as for public holiday calendars. */
  record Unpublished() implements OperatingHours {}

  /**
   * Published hours.
   *
   * @param opens null when the operator publishes only cutoffs (FedACH) or never closes
   * @param closes null for the same reasons as {@code opens}
   * @param opensPreviousDay the business day opens at {@code opens} on the calendar day before it
   *     (Fedwire)
   */
  record Published(
      boolean alwaysOpen,
      LocalTime opens,
      LocalTime closes,
      boolean opensPreviousDay,
      String note,
      List<Cutoff> cutoffs)
      implements OperatingHours {

    public Published {
      Objects.requireNonNull(note, "note");
      cutoffs = List.copyOf(cutoffs);
    }
  }

  /** The last time a kind of payment is accepted on a business day. */
  record Cutoff(String name, LocalTime deadline) {

    public Cutoff {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(deadline, "deadline");
    }
  }
}
