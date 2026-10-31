package com.zeitwide.api.operations.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.operations.app.OperationsRepository;
import com.zeitwide.api.operations.domain.ReleaseSummary;
import com.zeitwide.api.operations.domain.SourceHealth;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOperationsRepository implements OperationsRepository {

  private static final TypeReference<Map<String, Long>> REVISIONS = new TypeReference<>() {};
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcOperationsRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** The latest snapshot per source comes from source_snapshot_source_fetched_idx. */
  @Override
  public List<SourceHealth> sources() {
    return jdbc.sql(
            """
            SELECT s.slug, s.publisher, s.url, s.role, s.schedule, s.enabled,
                   COALESCE(st.consecutive_failures, 0) AS consecutive_failures,
                   st.last_checked_at, st.last_success_at, st.last_error, st.suspended_at,
                   latest.fetched_at AS last_snapshot_at, latest.sha256 AS last_snapshot_sha256
            FROM source AS s
            LEFT JOIN source_state AS st ON st.source_id = s.id
            LEFT JOIN LATERAL (
                SELECT ss.fetched_at, ss.sha256 FROM source_snapshot AS ss
                WHERE ss.source_id = s.id
                ORDER BY ss.fetched_at DESC
                LIMIT 1
            ) AS latest ON true
            ORDER BY s.slug
            """)
        .query(
            (row, rowNumber) -> {
              byte[] sha = row.getBytes("last_snapshot_sha256");
              return new SourceHealth(
                  row.getString("slug"),
                  row.getString("publisher"),
                  row.getString("url"),
                  row.getString("role"),
                  row.getString("schedule"),
                  row.getBoolean("enabled"),
                  row.getInt("consecutive_failures"),
                  instantOrNull(row, "last_checked_at"),
                  instantOrNull(row, "last_success_at"),
                  instantOrNull(row, "last_snapshot_at"),
                  sha == null ? null : HexFormat.of().formatHex(sha),
                  row.getString("last_error"),
                  instantOrNull(row, "suspended_at"));
            })
        .list();
  }

  @Override
  public List<ReleaseSummary> releases(int limit) {
    return jdbc.sql(
            """
            SELECT id, as_of, calendar_revisions::text AS calendar_revisions, export_sha256,
                   created_at
            FROM dataset_release
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (row, rowNumber) ->
                new ReleaseSummary(
                    row.getString("id"),
                    instantOrNull(row, "as_of"),
                    revisions(row.getString("calendar_revisions")),
                    HexFormat.of().formatHex(row.getBytes("export_sha256")),
                    instantOrNull(row, "created_at")))
        .list();
  }

  private Map<String, Long> revisions(String text) {
    try {
      return json.readValue(text, REVISIONS);
    } catch (IOException exception) {
      throw new IllegalStateException("Stored release revisions are not valid JSON", exception);
    }
  }

  private static Instant instantOrNull(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
