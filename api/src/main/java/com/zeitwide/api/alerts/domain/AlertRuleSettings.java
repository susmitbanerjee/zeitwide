package com.zeitwide.api.alerts.domain;

import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.domain.ChangeType;
import java.util.List;
import java.util.Objects;

/** What an alert rule matches: changes of these types, on these calendars, at this notice. */
public record AlertRuleSettings(
    String name, List<CalendarId> calendars, List<ChangeType> events, Integer maxNoticeDays) {

  public static final int MAX_NAME_LENGTH = 100;
  public static final int MAX_CALENDARS = 20;
  public static final int MAX_NOTICE_DAYS = 3650;

  public AlertRuleSettings {
    Objects.requireNonNull(name, "name");
    name = name.strip();
    if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("A name is 1 to " + MAX_NAME_LENGTH + " characters");
    }
    calendars = List.copyOf(calendars);
    events = List.copyOf(events);
    if (calendars.isEmpty()
        || calendars.size() > MAX_CALENDARS
        || calendars.stream().distinct().count() != calendars.size()) {
      throw new IllegalArgumentException(
          "A rule names 1 to " + MAX_CALENDARS + " different calendars");
    }
    if (events.isEmpty() || events.stream().distinct().count() != events.size()) {
      throw new IllegalArgumentException("A rule names at least one change type, each once");
    }
    if (maxNoticeDays != null && (maxNoticeDays < 0 || maxNoticeDays > MAX_NOTICE_DAYS)) {
      throw new IllegalArgumentException("Notice is 0 to " + MAX_NOTICE_DAYS + " days");
    }
  }

  /** Whether a published change alerts under this rule; the outbox fan-out runs the same test. */
  public boolean matches(CalendarId calendar, ChangeType type, int noticeDays) {
    return calendars.contains(calendar)
        && events.contains(type)
        && (maxNoticeDays == null || noticeDays <= maxNoticeDays);
  }
}
