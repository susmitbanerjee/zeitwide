package com.settlecal.api.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.settlecal.api.PostgresJdbcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks that the change feed and the outbox drain can use their indexes. Sequential scans are
 * switched off, because on near-empty test tables the planner would rightly prefer them.
 */
@PostgresJdbcTest
class ChangeFeedIndexTest {

  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void disableSequentialScans() {
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
  }

  @Test
  void shouldServeChangesSinceATimeFromPublishedIndex() {
    String plan =
        explain(
            """
            SELECT id, change_type, calendar_id, entry_date FROM change_event
            WHERE (published_at, id) > (TIMESTAMPTZ '2026-01-01Z', 0)
            ORDER BY published_at, id
            LIMIT 100
            """);

    assertThat(plan).contains("change_event_published_idx");
  }

  @Test
  void shouldServeOneCalendarsChangesFromCalendarIndex() {
    String plan =
        explain(
            """
            SELECT id, change_type, entry_date FROM change_event
            WHERE calendar_id = 'US-FEDWIRE'
              AND (published_at, id) > (TIMESTAMPTZ '2026-01-01Z', 0)
            ORDER BY published_at, id
            LIMIT 100
            """);

    assertThat(plan).contains("change_event_calendar_published_idx");
  }

  @Test
  void shouldServeOutboxDrainFromPendingIndex() {
    String plan =
        explain(
            """
            SELECT id, change_event_id, payload FROM outbox
            WHERE status = 'pending' AND next_attempt_at <= now()
            ORDER BY next_attempt_at, id
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """);

    assertThat(plan).contains("outbox_pending_idx");
  }

  private String explain(String sql) {
    return String.join("\n", jdbc.sql("EXPLAIN " + sql).query(String.class).list());
  }
}
