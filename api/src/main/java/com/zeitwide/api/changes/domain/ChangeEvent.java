package com.zeitwide.api.changes.domain;

import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record ChangeEvent(
    long id,
    ChangeType type,
    CalendarId calendarId,
    LocalDate entryDate,
    Map<String, Object> beforeState,
    Map<String, Object> afterState,
    int noticeDays,
    Instant publishedAt) {}
