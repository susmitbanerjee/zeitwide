package com.settlecal.api.answers.domain;

import com.settlecal.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the engine answers from: a dataset release's calendars and their history.
 *
 * @param asOf the recorded-time instant the dataset captures
 */
public record Dataset(
    DatasetRelease release, Instant asOf, Map<CalendarId, CalendarHistory> calendars) {

  public Dataset {
    Objects.requireNonNull(release, "release");
    Objects.requireNonNull(asOf, "asOf");
    calendars = Map.copyOf(calendars);
  }

  public CalendarHistory calendar(CalendarId id) {
    CalendarHistory calendar = calendars.get(id);
    if (calendar == null) {
      throw new UnknownCalendarException(id);
    }
    return calendar;
  }
}
