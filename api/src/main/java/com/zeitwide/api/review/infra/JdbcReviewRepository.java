package com.zeitwide.api.review.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.calendars.domain.Basis;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.review.app.ReviewRepository;
import com.zeitwide.api.review.domain.Candidate;
import com.zeitwide.api.review.domain.CandidateSnapshot;
import com.zeitwide.api.review.domain.CandidateStatus;
import com.zeitwide.api.review.domain.EntryState;
import com.zeitwide.api.review.domain.ReviewDecision;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReviewRepository implements ReviewRepository {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
  private static final String CANDIDATE_COLUMNS =
      """
      id, calendar_id, entry_date, before_state::text AS before_state,
      after_state::text AS after_state, corroboration, source_count, status, created_at
      """;

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcReviewRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public List<Candidate> list(CandidateStatus status, LocalDate today, int limit) {
    return jdbc.sql(
            "SELECT "
                + CANDIDATE_COLUMNS
                + """
                FROM candidate_change
                WHERE status = :status
                ORDER BY (entry_date - :today) < :urgent DESC, entry_date, id
                LIMIT :limit
                """)
        .param("status", status.code())
        .param("today", today)
        .param("urgent", Candidate.URGENT_NOTICE_DAYS)
        .param("limit", limit)
        .query((row, rowNumber) -> candidate(row))
        .list();
  }

  @Override
  public Optional<Candidate> find(long id) {
    return jdbc.sql("SELECT " + CANDIDATE_COLUMNS + " FROM candidate_change WHERE id = :id")
        .param("id", id)
        .query((row, rowNumber) -> candidate(row))
        .optional();
  }

  @Override
  public Optional<EntryState> current(CalendarId calendarId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT is_open, reason, basis FROM calendar_entry
            WHERE calendar_id = :calendar AND entry_date = :date AND upper_inf(recorded_during)
            """)
        .param("calendar", calendarId.value())
        .param("date", date)
        .query(
            (row, rowNumber) ->
                new EntryState(
                    row.getBoolean("is_open"),
                    row.getString("reason"),
                    Basis.fromCode(row.getString("basis"))))
        .optional();
  }

  @Override
  public List<CandidateSnapshot> snapshots(long candidateId) {
    return jdbc.sql(
            """
            SELECT s.slug, s.publisher, s.url, s.role, ss.fetched_at, ss.sha256, ss.http_status,
                   ss.content_type, ss.byte_size, o.is_open, o.reason, o.parser_version
            FROM candidate_observation AS co
            JOIN observation AS o ON o.id = co.observation_id
            JOIN source_snapshot AS ss ON ss.id = o.snapshot_id
            JOIN source AS s ON s.id = ss.source_id
            WHERE co.candidate_id = :candidate
            ORDER BY s.role, s.slug, ss.fetched_at
            """)
        .param("candidate", candidateId)
        .query(
            (row, rowNumber) ->
                new CandidateSnapshot(
                    row.getString("slug"),
                    row.getString("publisher"),
                    row.getString("url"),
                    row.getString("role"),
                    instant(row, "fetched_at"),
                    HexFormat.of().formatHex(row.getBytes("sha256")),
                    row.getInt("http_status"),
                    row.getString("content_type"),
                    row.getLong("byte_size"),
                    row.getBoolean("is_open"),
                    row.getString("reason"),
                    row.getString("parser_version")))
        .list();
  }

  @Override
  public Optional<Recorded> decision(long candidateId) {
    return jdbc.sql(
            """
            SELECT reviewer_subject, review_reason, reviewed_at FROM candidate_change
            WHERE id = :id AND reviewed_at IS NOT NULL
            """)
        .param("id", candidateId)
        .query(
            (row, rowNumber) ->
                new Recorded(
                    row.getString("reviewer_subject"),
                    row.getString("review_reason"),
                    instant(row, "reviewed_at")))
        .optional();
  }

  @Override
  public boolean decide(long candidateId, ReviewDecision decision, Instant now) {
    return jdbc.sql(
                """
                UPDATE candidate_change
                SET status = :status, reviewer_subject = :reviewer, review_reason = :reason,
                    reviewed_at = :now
                WHERE id = :id AND status = 'pending'
                """)
            .param("status", decision.resultingStatus().code())
            .param("reviewer", decision.reviewer())
            .param("reason", decision.reason())
            .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("id", candidateId)
            .update()
        == 1;
  }

  private Candidate candidate(ResultSet row) throws SQLException {
    String before = row.getString("before_state");
    return new Candidate(
        row.getLong("id"),
        new CalendarId(row.getString("calendar_id")),
        row.getObject("entry_date", LocalDate.class),
        before == null ? null : state(before),
        state(row.getString("after_state")),
        Candidate.Corroboration.fromCode(row.getString("corroboration")),
        row.getInt("source_count"),
        CandidateStatus.fromCode(row.getString("status")),
        instant(row, "created_at"));
  }

  /** The pipeline stores states as {@code {"is_open", "reason", "basis"?}}. */
  private EntryState state(String text) {
    Map<String, Object> value;
    try {
      value = json.readValue(text, JSON_OBJECT);
    } catch (IOException exception) {
      throw new IllegalStateException("A stored candidate state is not valid JSON", exception);
    }
    Object basis = value.get("basis");
    return new EntryState(
        Boolean.TRUE.equals(value.get("is_open")),
        String.valueOf(value.get("reason")),
        basis instanceof String code ? Basis.fromCode(code) : null);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }
}
