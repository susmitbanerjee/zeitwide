package com.zeitwide.api.calendars.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One version of what Zeitwide knows about a date on a calendar.
 *
 * @param recordedFrom when Zeitwide started believing this version
 */
public record CalendarEntry(
    CalendarId calendarId,
    LocalDate entryDate,
    boolean open,
    String reason,
    Basis basis,
    long revision,
    Instant recordedFrom) {

  private static final int MAX_REASON_LENGTH = 500;

  public CalendarEntry {
    Objects.requireNonNull(calendarId, "calendarId");
    Objects.requireNonNull(entryDate, "entryDate");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(basis, "basis");
    Objects.requireNonNull(recordedFrom, "recordedFrom");
    if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException(
          "reason must be 1 to " + MAX_REASON_LENGTH + " characters");
    }
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be positive");
    }
  }
}
