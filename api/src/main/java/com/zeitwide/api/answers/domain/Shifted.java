package com.zeitwide.api.answers.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Where moving by business days landed, with the evidence for every day looked at.
 *
 * @param landed the answer for the day the move landed on
 * @param skipped the days passed over because they weren't business days, in the order met
 */
public record Shifted(DayAnswer landed, List<DayAnswer> skipped) {

  public Shifted {
    Objects.requireNonNull(landed, "landed");
    skipped = List.copyOf(skipped);
  }

  public LocalDate date() {
    return landed.date();
  }

  public List<LocalDate> skippedDates() {
    return skipped.stream().map(DayAnswer::date).toList();
  }
}
