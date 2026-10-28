package com.settlecal.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.settlecal.api.PostgresJdbcTest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@PostgresJdbcTest
class CleanupFunctionTest {

  private static final String ACCOUNT = "00000000-0000-0000-0000-000000000001";
  private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000002";

  @Autowired private JdbcClient jdbc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void createAccountAndWebhook() {
    jdbcTemplate.execute(
        """
        INSERT INTO account (id, name) VALUES ('%1$s', 'Acme');
        INSERT INTO calendar (id, name, kind, timezone, weekend_rule)
            VALUES ('US-FEDWIRE', 'Fedwire Funds Service', 'rail', 'America/New_York', '{}');
        SELECT publish_entry_version('US-FEDWIRE', DATE '2026-11-26', false, 'Thanksgiving',
                                     'published', 1, TIMESTAMPTZ '2026-01-01Z');
        INSERT INTO change_event (change_type, calendar_id, entry_date, entry_id, after_state, notice_days)
            SELECT 'closure_added', calendar_id, entry_date, id, '{}', 329 FROM calendar_entry;
        INSERT INTO outbox (change_event_id, event_type, payload)
            SELECT id, change_type, '{}' FROM change_event;
        INSERT INTO webhook_subscription (id, account_id, url, signing_secret_ciphertext, event_types)
            VALUES ('%2$s', '%1$s', 'https://hooks.example.com/settlecal', '\\x00',
                    '{closure_added}');
        """
            .formatted(ACCOUNT, SUBSCRIPTION));
  }

  @Test
  void shouldDeleteUsageOlderThanRetention() {
    addUsage(40);
    addUsage(1);

    cleanup();

    assertThat(count("usage_event")).isEqualTo(1L);
  }

  @Test
  void shouldRollUpUsageBeforeDeletingIt() {
    addUsage(40);
    addUsage(40);

    cleanup();

    assertThat(
            jdbc.sql("SELECT request_count FROM usage_daily WHERE usage_date = :day")
                .param("day", LocalDate.now(ZoneOffset.UTC).minusDays(40))
                .query(Long.class)
                .single())
        .isEqualTo(2L);
  }

  @Test
  void shouldGiveSameRollUpWhenRunTwice() {
    addUsage(3);
    addUsage(3);

    rollUpUntilToday();
    rollUpUntilToday();

    assertThat(jdbc.sql("SELECT sum(request_count) FROM usage_daily").query(Long.class).single())
        .isEqualTo(2L);
  }

  @Test
  void shouldDeleteWebhookDeliveriesOlderThanRetention() {
    jdbcTemplate.execute(
        """
        INSERT INTO webhook_delivery (subscription_id, outbox_id, status, created_at)
            SELECT '%s', id, 'succeeded', now() - interval '40 days' FROM outbox;
        """
            .formatted(SUBSCRIPTION));

    cleanup();

    assertThat(count("webhook_delivery")).isZero();
  }

  @Test
  void shouldKeepRecentWebhookDeliveries() {
    jdbcTemplate.execute(
        """
        INSERT INTO webhook_delivery (subscription_id, outbox_id, status)
            SELECT '%s', id, 'pending' FROM outbox;
        """
            .formatted(SUBSCRIPTION));

    cleanup();

    assertThat(count("webhook_delivery")).isEqualTo(1L);
  }

  @Test
  void shouldDeleteOnlyExpiredRateLimitBuckets() {
    long nowMillis = System.currentTimeMillis();
    jdbc.sql(
            """
            INSERT INTO rate_limit_bucket (id, state, expires_at)
            VALUES (1, '\\x00', :expired), (2, '\\x00', :live), (3, '\\x00', NULL)
            """)
        .param("expired", nowMillis - ChronoUnit.HOURS.getDuration().toMillis())
        .param("live", nowMillis + ChronoUnit.HOURS.getDuration().toMillis())
        .update();

    cleanup();

    assertThat(jdbc.sql("SELECT id FROM rate_limit_bucket ORDER BY id").query(Long.class).list())
        .containsExactly(2L, 3L);
  }

  @Test
  void shouldDeleteOnlyOldFinishedQueueJobs() {
    // Jobs move todo -> doing -> succeeded like a worker runs them, so Procrastinate's
    // triggers record the usual events; then the old job's events are backdated.
    jdbcTemplate.execute(
        """
        INSERT INTO procrastinate_jobs (queue_name, task_name, lock)
            VALUES ('sources', 'check_source', 'old-done'),
                   ('sources', 'check_source', 'new-done'),
                   ('sources', 'check_source', 'old-todo');
        UPDATE procrastinate_jobs SET status = 'doing' WHERE lock <> 'old-todo';
        UPDATE procrastinate_jobs SET status = 'succeeded' WHERE lock <> 'old-todo';
        UPDATE procrastinate_events SET at = now() - interval '40 days'
            WHERE job_id IN (SELECT id FROM procrastinate_jobs WHERE lock LIKE 'old-%');
        """);

    cleanup();

    assertThat(
            jdbc.sql("SELECT lock FROM procrastinate_jobs ORDER BY lock")
                .query(String.class)
                .list())
        .containsExactly("new-done", "old-todo");
  }

  @Test
  void shouldReportEveryTableItCleans() {
    assertThat(jdbc.sql("SELECT table_name FROM cleanup_old_rows()").query(String.class).list())
        .containsExactly(
            "usage_event", "webhook_delivery", "rate_limit_bucket", "procrastinate_jobs");
  }

  @Test
  void shouldRejectRetentionShorterThanOneDay() {
    assertThatThrownBy(
            () ->
                jdbc.sql("SELECT * FROM cleanup_old_rows(interval '1 hour')").query().listOfRows())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("at least one day");
  }

  private void addUsage(int daysAgo) {
    jdbc.sql(
            """
            INSERT INTO usage_event (account_id, feature, occurred_at)
            VALUES (CAST(:account AS uuid), 'answers', now() - make_interval(days => :daysAgo))
            """)
        .param("account", ACCOUNT)
        .param("daysAgo", daysAgo)
        .update();
  }

  private void cleanup() {
    jdbc.sql("SELECT * FROM cleanup_old_rows()").query().listOfRows();
  }

  private void rollUpUntilToday() {
    jdbc.sql("SELECT roll_up_usage(date_trunc('day', now(), 'UTC'))").query(Long.class).single();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
