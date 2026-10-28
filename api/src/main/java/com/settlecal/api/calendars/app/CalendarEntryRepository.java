package com.settlecal.api.calendars.app;

import com.settlecal.api.calendars.domain.CalendarEntry;
import com.settlecal.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Read side of the bitemporal calendar entries. The pipeline writes them. */
public interface CalendarEntryRepository {

  /** The version of the entry that SettleCal held at {@code recordedAt}, if there was one. */
  Optional<CalendarEntry> findAsOf(CalendarId calendarId, LocalDate entryDate, Instant recordedAt);

  /** Current closures from {@code from} to {@code to}, both inclusive, in date order. */
  List<CalendarEntry> findCurrentClosures(CalendarId calendarId, LocalDate from, LocalDate to);
}
