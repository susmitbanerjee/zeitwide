package com.zeitwide.api.answers.app;

import com.zeitwide.api.answers.domain.RangeDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A calendar's answers for a run of dates, and the calendar's date today, so no client has to work
 * out either.
 */
public record DayRange(LocalDate today, LocalDate from, LocalDate to, List<RangeDay> days) {

  public DayRange {
    Objects.requireNonNull(today, "today");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    days = List.copyOf(days);
  }
}
