package com.settlecal.api.calendars.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CalendarEntryTest {

  private static final CalendarId FEDWIRE = new CalendarId("US-FEDWIRE");
  private static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);
  private static final Instant RECORDED = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void shouldKeepAValidClosure() {
    CalendarEntry entry = entry("Thanksgiving", 1);

    assertThat(entry.open()).isFalse();
  }

  @Test
  void shouldRejectBlankReason() {
    assertThatThrownBy(() -> entry(" ", 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectReasonLongerThan500Characters() {
    assertThatThrownBy(() -> entry("x".repeat(501), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectNonPositiveRevision() {
    assertThatThrownBy(() -> entry("Thanksgiving", 0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static CalendarEntry entry(String reason, long revision) {
    return new CalendarEntry(
        FEDWIRE, THANKSGIVING, false, reason, Basis.PUBLISHED, revision, RECORDED);
  }
}
