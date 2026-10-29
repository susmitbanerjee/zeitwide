package com.settlecal.api.answers.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Whether a date is a business day, with each calendar's answer as evidence. */
public record DayAnswer(LocalDate date, boolean businessDay, List<CalendarDay> calendars) {

  public DayAnswer {
    Objects.requireNonNull(date, "date");
    calendars = List.copyOf(calendars);
  }
}
