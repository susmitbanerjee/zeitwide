package com.zeitwide.api.answers.domain;

import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.LocalDate;

/** The date is outside what the calendar's sources had published, so it is never guessed. */
public final class OutOfRangeException extends AnswerException {

  private static final long serialVersionUID = 1L;

  // The ID's text, not the record: exceptions are serializable and CalendarId isn't.
  private final String calendarId;
  private final LocalDate date;

  public OutOfRangeException(CalendarId calendarId, LocalDate date) {
    super(calendarId.value() + " has no source coverage for " + date);
    this.calendarId = calendarId.value();
    this.date = date;
  }

  public CalendarId calendarId() {
    return new CalendarId(calendarId);
  }

  public LocalDate date() {
    return date;
  }

  @Override
  public String code() {
    return "out_of_range";
  }
}
