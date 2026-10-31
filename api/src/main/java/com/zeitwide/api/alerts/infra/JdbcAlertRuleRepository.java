package com.zeitwide.api.alerts.infra;

import com.zeitwide.api.alerts.app.AlertRuleRepository;
import com.zeitwide.api.alerts.domain.AlertRule;
import com.zeitwide.api.alerts.domain.AlertRuleSettings;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.domain.ChangeType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAlertRuleRepository implements AlertRuleRepository {

  private static final String COLUMNS =
      """
      id, name, calendar_ids, event_types::text[] AS event_types, max_notice_days, created_at
      """;
  private final JdbcClient jdbc;

  JdbcAlertRuleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockForAccount(UUID accountId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0)) IS NULL")
        .param("lock_key", "alert_rule:" + accountId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public long countForAccount(UUID accountId) {
    return jdbc.sql("SELECT count(*) FROM alert_rule WHERE account_id = :account")
        .param("account", accountId)
        .query(Long.class)
        .single();
  }

  @Override
  public List<AlertRule> listForAccount(UUID accountId, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                FROM alert_rule
                WHERE account_id = :account
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
        .param("account", accountId)
        .param("limit", limit)
        .query((row, rowNumber) -> rule(row))
        .list();
  }

  @Override
  public Optional<AlertRule> create(
      UUID accountId, AlertRuleSettings settings, byte[] idempotencyHash) {
    return jdbc.sql(
            """
            INSERT INTO alert_rule
                (account_id, name, calendar_ids, event_types, max_notice_days,
                 idempotency_key_hash)
            VALUES (:account, :name, CAST(:calendars AS text[]), CAST(:events AS change_type[]),
                    :maxNoticeDays, :idempotencyHash)
            ON CONFLICT (account_id, idempotency_key_hash)
                WHERE idempotency_key_hash IS NOT NULL
                DO NOTHING
            RETURNING
            """
                + COLUMNS)
        .param("account", accountId)
        .param("name", settings.name())
        .param("calendars", postgresArray(settings.calendars(), CalendarId::value))
        .param("events", postgresArray(settings.events(), ChangeType::code))
        .param("maxNoticeDays", settings.maxNoticeDays())
        .param("idempotencyHash", idempotencyHash)
        .query((row, rowNumber) -> rule(row))
        .optional();
  }

  @Override
  public Optional<AlertRule> update(UUID accountId, UUID id, AlertRuleSettings settings) {
    return jdbc.sql(
            """
            UPDATE alert_rule
            SET name = :name, calendar_ids = CAST(:calendars AS text[]),
                event_types = CAST(:events AS change_type[]), max_notice_days = :maxNoticeDays
            WHERE id = :id AND account_id = :account
            RETURNING
            """
                + COLUMNS)
        .param("name", settings.name())
        .param("calendars", postgresArray(settings.calendars(), CalendarId::value))
        .param("events", postgresArray(settings.events(), ChangeType::code))
        .param("maxNoticeDays", settings.maxNoticeDays())
        .param("id", id)
        .param("account", accountId)
        .query((row, rowNumber) -> rule(row))
        .optional();
  }

  @Override
  public boolean delete(UUID accountId, UUID id) {
    return jdbc.sql("DELETE FROM alert_rule WHERE id = :id AND account_id = :account")
            .param("id", id)
            .param("account", accountId)
            .update()
        == 1;
  }

  private static AlertRule rule(ResultSet row) throws SQLException {
    Integer maxNoticeDays = row.getObject("max_notice_days", Integer.class);
    return new AlertRule(
        row.getObject("id", UUID.class),
        new AlertRuleSettings(
            row.getString("name"),
            texts(row.getArray("calendar_ids")).stream().map(CalendarId::new).toList(),
            texts(row.getArray("event_types")).stream().map(ChangeType::fromCode).toList(),
            maxNoticeDays),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static List<String> texts(Array array) throws SQLException {
    Object[] values = (Object[]) array.getArray();
    List<String> result = new ArrayList<>(values.length);
    for (Object value : values) {
      result.add(value.toString());
    }
    return result;
  }

  /** Values are validated IDs and enum codes, so none needs quoting inside the literal. */
  private static <T> String postgresArray(List<T> values, Function<T, String> text) {
    return "{" + String.join(",", values.stream().map(text).toList()) + "}";
  }
}
