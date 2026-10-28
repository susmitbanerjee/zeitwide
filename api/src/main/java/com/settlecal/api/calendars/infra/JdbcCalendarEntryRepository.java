package com.settlecal.api.calendars.infra;

import com.settlecal.api.calendars.app.CalendarEntryRepository;
import com.settlecal.api.calendars.domain.Basis;
import com.settlecal.api.calendars.domain.CalendarEntry;
import com.settlecal.api.calendars.domain.CalendarId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCalendarEntryRepository implements CalendarEntryRepository {

  private static final String COLUMNS =
      """
      SELECT calendar_id, entry_date, is_open, reason, basis, revision,
             lower(recorded_during) AS recorded_from
      FROM calendar_entry
      """;

  // Served by the GiST index behind calendar_entry_no_overlap.
  static final String AS_OF =
      COLUMNS
          + """
          WHERE calendar_id = :calendarId
            AND entry_date = :entryDate
            AND recorded_during @> CAST(:recordedAt AS timestamptz)
          """;

  // Served by calendar_entry_current_closures_idx; the predicate must match it.
  static final String CURRENT_CLOSURES =
      COLUMNS
          + """
          WHERE calendar_id = :calendarId
            AND entry_date BETWEEN :from AND :to
            AND NOT is_open
            AND upper_inf(recorded_during)
          ORDER BY entry_date
          """;

  private final JdbcClient jdbc;

  JdbcCalendarEntryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CalendarEntry> findAsOf(
      CalendarId calendarId, LocalDate entryDate, Instant recordedAt) {
    return jdbc.sql(AS_OF)
        .param("calendarId", calendarId.value())
        .param("entryDate", entryDate)
        // The PostgreSQL driver binds OffsetDateTime as timestamptz, but not Instant.
        .param("recordedAt", recordedAt.atOffset(ZoneOffset.UTC))
        .query(JdbcCalendarEntryRepository::toEntry)
        .optional();
  }

  @Override
  public List<CalendarEntry> findCurrentClosures(
      CalendarId calendarId, LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("to must not be before from");
    }
    return jdbc.sql(CURRENT_CLOSURES)
        .param("calendarId", calendarId.value())
        .param("from", from)
        .param("to", to)
        .query(JdbcCalendarEntryRepository::toEntry)
        .list();
  }

  private static CalendarEntry toEntry(ResultSet rs, int rowNum) throws SQLException {
    return new CalendarEntry(
        new CalendarId(rs.getString("calendar_id")),
        rs.getObject("entry_date", LocalDate.class),
        rs.getBoolean("is_open"),
        rs.getString("reason"),
        Basis.fromCode(rs.getString("basis")),
        rs.getLong("revision"),
        rs.getObject("recorded_from", OffsetDateTime.class).toInstant());
  }
}
