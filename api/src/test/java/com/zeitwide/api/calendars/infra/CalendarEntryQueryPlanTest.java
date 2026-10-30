package com.zeitwide.api.calendars.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.PostgresJdbcTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks that the repository's request-path queries can use their indexes. Sequential scans are
 * switched off, because on near-empty test tables the planner would rightly prefer them.
 */
@PostgresJdbcTest
class CalendarEntryQueryPlanTest {

  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void disableSequentialScans() {
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
  }

  @Test
  void shouldServeAsOfLookupFromExclusionIndex() {
    String plan =
        explain(
            jdbc.sql("EXPLAIN " + JdbcCalendarEntryRepository.AS_OF)
                .param("calendarId", "US-FEDWIRE")
                .param("entryDate", LocalDate.of(2026, 11, 26))
                .param("recordedAt", OffsetDateTime.parse("2026-01-15T00:00:00Z"))
                .query(String.class)
                .list());

    assertThat(plan).contains("calendar_entry_no_overlap");
  }

  @Test
  void shouldServeCurrentClosuresFromPartialIndex() {
    String plan =
        explain(
            jdbc.sql("EXPLAIN " + JdbcCalendarEntryRepository.CURRENT_CLOSURES)
                .param("calendarId", "US-FEDWIRE")
                .param("from", LocalDate.of(2026, 1, 1))
                .param("to", LocalDate.of(2026, 12, 31))
                .query(String.class)
                .list());

    assertThat(plan).contains("calendar_entry_current_closures_idx");
  }

  private static String explain(List<String> lines) {
    return String.join("\n", lines);
  }
}
