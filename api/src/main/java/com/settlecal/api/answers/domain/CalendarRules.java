package com.settlecal.api.answers.domain;

import com.settlecal.api.calendars.domain.CalendarId;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * What a calendar is and its weekend rule, as the dataset release defines them.
 *
 * @param closesOnHolidays false for calendars that close on no holiday (FedNow), which have no
 *     coverage limit
 */
public record CalendarRules(
    CalendarId id,
    String name,
    CalendarKind kind,
    ZoneId timezone,
    Set<DayOfWeek> weekend,
    boolean closesOnHolidays) {

  public CalendarRules {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(timezone, "timezone");
    weekend = Set.copyOf(weekend);
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }
}
