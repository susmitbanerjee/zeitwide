package com.zeitwide.api.changes.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.app.ChangeRepository;
import com.zeitwide.api.changes.domain.ChangeCursor;
import com.zeitwide.api.changes.domain.ChangeEvent;
import com.zeitwide.api.changes.domain.ChangeType;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcChangeRepository implements ChangeRepository {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcChangeRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public List<ChangeEvent> page(
      Instant since,
      ChangeCursor cursor,
      List<CalendarId> calendars,
      List<ChangeType> types,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, change_type, calendar_id, entry_date, before_state::text AS before_state,
                   after_state::text AS after_state, notice_days, published_at
            FROM change_event
            WHERE published_at >= :since
              AND (published_at, id) > (:cursor_at, :cursor_id)
            """);
    if (!calendars.isEmpty()) {
      sql.append(" AND calendar_id IN (:calendars)");
    }
    if (!types.isEmpty()) {
      sql.append(" AND change_type IN (:types)");
    }
    sql.append(" ORDER BY published_at ASC, id ASC LIMIT :limit");

    var query =
        jdbc.sql(sql.toString())
            .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
            .param("cursor_at", OffsetDateTime.ofInstant(cursor.publishedAt(), ZoneOffset.UTC))
            .param("cursor_id", cursor.id())
            .param("limit", limit);
    if (!calendars.isEmpty()) {
      query = query.param("calendars", calendars.stream().map(CalendarId::value).toList());
    }
    if (!types.isEmpty()) {
      query = query.param("types", types.stream().map(ChangeType::code).toList());
    }
    RowMapper<ChangeEvent> mapper =
        (row, rowNumber) ->
            new ChangeEvent(
                row.getLong("id"),
                ChangeType.fromCode(row.getString("change_type")),
                new CalendarId(row.getString("calendar_id")),
                row.getDate("entry_date").toLocalDate(),
                jsonObjectOrNull(row.getString("before_state")),
                jsonObject(row.getString("after_state")),
                row.getInt("notice_days"),
                row.getObject("published_at", OffsetDateTime.class).toInstant());
    return query.query(mapper).list();
  }

  private Map<String, Object> jsonObjectOrNull(String value) {
    return value == null ? null : jsonObject(value);
  }

  private Map<String, Object> jsonObject(String value) {
    try {
      return json.readValue(value, JSON_OBJECT);
    } catch (IOException exception) {
      throw new IllegalStateException("A stored change event is not valid JSON", exception);
    }
  }
}
