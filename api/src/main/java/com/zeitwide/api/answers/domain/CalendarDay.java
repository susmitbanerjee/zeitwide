package com.zeitwide.api.answers.domain;

import com.zeitwide.api.calendars.domain.CalendarId;

/** One calendar's answer for one date, and what decided it. */
public sealed interface CalendarDay {

  CalendarId calendarId();

  boolean open();

  /** An entry decided: a closure, or an open day overriding the weekend rule. */
  record ByEntry(CalendarId calendarId, EntryVersion entry) implements CalendarDay {
    @Override
    public boolean open() {
      return entry.open();
    }
  }

  /** No entry, so the weekend rule decided. */
  record ByWeekendRule(CalendarId calendarId, boolean open) implements CalendarDay {}
}
