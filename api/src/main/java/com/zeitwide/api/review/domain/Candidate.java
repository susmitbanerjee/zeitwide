package com.zeitwide.api.review.domain;

import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A proposed change to one date on one calendar. {@code before} is null for a date that had no
 * entry when the pipeline proposed it.
 */
public record Candidate(
    long id,
    CalendarId calendarId,
    LocalDate entryDate,
    EntryState before,
    EntryState after,
    Corroboration corroboration,
    int sourceCount,
    CandidateStatus status,
    Instant createdAt) {

  /** Changes announced with less notice than this jump the review queue (pipeline: publish.py). */
  public static final int URGENT_NOTICE_DAYS = 14;

  public Candidate {
    Objects.requireNonNull(calendarId, "calendarId");
    Objects.requireNonNull(entryDate, "entryDate");
    Objects.requireNonNull(after, "after");
    Objects.requireNonNull(corroboration, "corroboration");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Days from {@code today} to the date; negative once the date has passed. */
  public int noticeDays(LocalDate today) {
    return Math.toIntExact(ChronoUnit.DAYS.between(today, entryDate));
  }

  public boolean urgent(LocalDate today) {
    return noticeDays(today) < URGENT_NOTICE_DAYS;
  }

  /** Whether a second, independent source agrees with the first. */
  public enum Corroboration {
    SINGLE_SOURCE("single_source"),
    CORROBORATED("corroborated");

    private final String code;

    Corroboration(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    public static Corroboration fromCode(String code) {
      for (Corroboration corroboration : values()) {
        if (corroboration.code.equals(code)) {
          return corroboration;
        }
      }
      throw new IllegalArgumentException("Unknown corroboration");
    }
  }
}
