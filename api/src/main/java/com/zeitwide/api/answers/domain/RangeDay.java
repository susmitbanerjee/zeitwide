package com.zeitwide.api.answers.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One date's answer in a range, with the nearest business days either side. Either neighbour is
 * null when finding it would leave the calendar's coverage.
 */
public record RangeDay(DayAnswer answer, LocalDate nextBusinessDay, LocalDate previousBusinessDay) {

  public RangeDay {
    Objects.requireNonNull(answer, "answer");
  }
}
