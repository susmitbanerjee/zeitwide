package com.zeitwide.api.answers.domain;

import com.zeitwide.api.calendars.domain.CalendarId;

/** The dataset has no calendar with this ID. */
public final class UnknownCalendarException extends AnswerException {

  private static final long serialVersionUID = 1L;

  // The ID's text, not the record: exceptions are serializable and CalendarId isn't.
  private final String calendarId;

  public UnknownCalendarException(CalendarId calendarId) {
    super("Unknown calendar " + calendarId.value());
    this.calendarId = calendarId.value();
  }

  public CalendarId calendarId() {
    return new CalendarId(calendarId);
  }

  @Override
  public String code() {
    return "unknown_calendar";
  }
}
