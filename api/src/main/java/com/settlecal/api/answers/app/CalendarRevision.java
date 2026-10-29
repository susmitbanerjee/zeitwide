package com.settlecal.api.answers.app;

import com.settlecal.api.calendars.domain.CalendarId;
import java.util.Objects;

/** A calendar's revision at the instant an answer was read as of. */
public record CalendarRevision(CalendarId calendarId, long revision) {

  public CalendarRevision {
    Objects.requireNonNull(calendarId, "calendarId");
  }
}
