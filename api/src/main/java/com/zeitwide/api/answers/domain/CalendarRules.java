package com.zeitwide.api.answers.domain;

import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * What a calendar is and its weekend rule, as the dataset release defines them.
 *
 * @param closesOnHolidays false for calendars that close on no holiday (FedNow), which have no
 *     coverage limit
 * @param hours when the calendar settles on a business day, for people to read
 */
public record CalendarRules(
    CalendarId id,
    String name,
    CalendarKind kind,
    ZoneId timezone,
    Set<DayOfWeek> weekend,
    boolean closesOnHolidays,
    OperatingHours hours) {

  public CalendarRules {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(hours, "hours");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(timezone, "timezone");
    weekend = Set.copyOf(weekend);
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  /** A calendar whose operator publishes no hours. */
  public CalendarRules(
      CalendarId id,
      String name,
      CalendarKind kind,
      ZoneId timezone,
      Set<DayOfWeek> weekend,
      boolean closesOnHolidays) {
    this(id, name, kind, timezone, weekend, closesOnHolidays, OperatingHours.UNPUBLISHED);
  }
}
