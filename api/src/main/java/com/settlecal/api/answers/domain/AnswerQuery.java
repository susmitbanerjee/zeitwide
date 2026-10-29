package com.settlecal.api.answers.domain;

import com.settlecal.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Which calendars to ask, how to combine them, and as of when.
 *
 * @param asOf the recorded-time instant to answer as of
 */
public record AnswerQuery(List<CalendarId> calendars, Combine combine, Instant asOf) {

  public static final int MAX_CALENDARS = 10;

  public AnswerQuery {
    calendars = List.copyOf(calendars);
    Objects.requireNonNull(combine, "combine");
    Objects.requireNonNull(asOf, "asOf");
    if (calendars.isEmpty() || calendars.size() > MAX_CALENDARS) {
      throw new IllegalArgumentException("Ask about 1 to " + MAX_CALENDARS + " calendars");
    }
    if (new HashSet<>(calendars).size() != calendars.size()) {
      throw new IllegalArgumentException("Each calendar may be listed once");
    }
  }
}
