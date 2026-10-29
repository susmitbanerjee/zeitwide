package com.settlecal.api.answers.domain;

import java.time.LocalDate;

/**
 * The dates a calendar can answer for at one moment. Outside it the engine answers {@code
 * out_of_range} instead of guessing from the weekend rule.
 */
public sealed interface Coverage {

  boolean contains(LocalDate date);

  /** A calendar that closes on no holiday: its weekend rule answers any date. */
  record Unlimited() implements Coverage {
    @Override
    public boolean contains(LocalDate date) {
      return true;
    }
  }

  /** Whole years, from the first to the last with a recorded entry. */
  record Years(int first, int last) implements Coverage {
    public Years {
      if (last < first) {
        throw new IllegalArgumentException("last must not be before first");
      }
    }

    public LocalDate from() {
      return LocalDate.of(first, 1, 1);
    }

    public LocalDate to() {
      return LocalDate.of(last, 12, 31);
    }

    @Override
    public boolean contains(LocalDate date) {
      return date.getYear() >= first && date.getYear() <= last;
    }
  }

  /** Nothing recorded yet. */
  record Nothing() implements Coverage {
    @Override
    public boolean contains(LocalDate date) {
      return false;
    }
  }
}
