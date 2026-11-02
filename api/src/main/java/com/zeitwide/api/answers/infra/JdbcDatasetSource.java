package com.zeitwide.api.answers.infra;

import com.zeitwide.api.answers.app.DatasetSource;
import com.zeitwide.api.answers.domain.CalendarHistory;
import com.zeitwide.api.answers.domain.CalendarKind;
import com.zeitwide.api.answers.domain.CalendarRules;
import com.zeitwide.api.answers.domain.Dataset;
import com.zeitwide.api.answers.domain.DatasetRelease;
import com.zeitwide.api.answers.domain.EntryVersion;
import com.zeitwide.api.answers.domain.OperatingHours;
import com.zeitwide.api.answers.domain.ReleaseStamp;
import com.zeitwide.api.answers.domain.SourceRef;
import com.zeitwide.api.calendars.domain.Basis;
import com.zeitwide.api.calendars.domain.CalendarId;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads a release's dataset: the same versions the pipeline exports for it. These queries run once
 * per release, not per request, so they scan the (small) tables in full.
 */
@Repository
class JdbcDatasetSource implements DatasetSource {

  static final String LATEST_RELEASE =
      """
      SELECT id, as_of FROM dataset_release
      ORDER BY as_of DESC, created_at DESC
      LIMIT 1
      """;

  static final String CALENDARS =
      """
      SELECT id, name, kind, timezone, closes_on_holidays,
             ARRAY(SELECT jsonb_array_elements_text(weekend_rule -> 'days')) AS weekend,
             operating_hours IS NOT NULL AS has_hours,
             COALESCE((operating_hours ->> 'always_open')::boolean, false) AS always_open,
             (operating_hours ->> 'opens')::time AS opens,
             (operating_hours ->> 'closes')::time AS closes,
             COALESCE((operating_hours ->> 'opens_previous_day')::boolean, false)
                 AS opens_previous_day,
             COALESCE(operating_hours ->> 'note', '') AS hours_note,
             ARRAY(SELECT c ->> 'name'
                   FROM jsonb_array_elements(COALESCE(operating_hours -> 'cutoffs', '[]'))
                        WITH ORDINALITY AS cutoff(c, n)
                   ORDER BY n) AS cutoff_names,
             ARRAY(SELECT c ->> 'deadline'
                   FROM jsonb_array_elements(COALESCE(operating_hours -> 'cutoffs', '[]'))
                        WITH ORDINALITY AS cutoff(c, n)
                   ORDER BY n) AS cutoff_deadlines
      FROM calendar
      ORDER BY id
      """;

  // As the pipeline's release export: a version replaced after as_of was current at as_of.
  static final String ENTRIES =
      """
      SELECT id, calendar_id, entry_date, is_open, reason, basis, revision,
             lower(recorded_during) AS recorded_from,
             CASE WHEN upper(recorded_during) <= :asOf THEN upper(recorded_during) END
                 AS recorded_to
      FROM calendar_entry
      WHERE lower(recorded_during) <= :asOf
      ORDER BY calendar_id, entry_date, lower(recorded_during)
      """;

  static final String SOURCES =
      """
      SELECT es.entry_id, s.slug, s.publisher, s.url, ss.sha256, ss.fetched_at
      FROM entry_source AS es
      JOIN calendar_entry AS e ON e.id = es.entry_id
      JOIN source_snapshot AS ss ON ss.id = es.snapshot_id
      JOIN source AS s ON s.id = ss.source_id
      WHERE lower(e.recorded_during) <= :asOf
      ORDER BY es.entry_id, s.slug, ss.fetched_at
      """;

  private final JdbcClient jdbc;

  JdbcDatasetSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private record Row(long id, CalendarId calendarId, EntryVersion version) {}

  private record Evidence(long entryId, SourceRef source) {}

  @Override
  public Optional<ReleaseStamp> latestRelease() {
    return jdbc.sql(LATEST_RELEASE)
        .query(
            (rs, rowNum) ->
                new ReleaseStamp(new DatasetRelease(rs.getString("id")), instant(rs, "as_of")))
        .optional();
  }

  @Override
  public Dataset load(ReleaseStamp release) {
    OffsetDateTime asOf = release.asOf().atOffset(ZoneOffset.UTC);
    Map<Long, List<SourceRef>> sources =
        jdbc.sql(SOURCES).param("asOf", asOf).query(JdbcDatasetSource::evidence).list().stream()
            .collect(
                Collectors.groupingBy(
                    Evidence::entryId, Collectors.mapping(Evidence::source, Collectors.toList())));
    Map<CalendarId, List<EntryVersion>> versions = new HashMap<>();
    jdbc.sql(ENTRIES)
        .param("asOf", asOf)
        .query((rs, rowNum) -> row(rs, sources))
        .list()
        .forEach(
            row ->
                versions
                    .computeIfAbsent(row.calendarId(), id -> new ArrayList<>())
                    .add(row.version()));
    Map<CalendarId, CalendarHistory> calendars =
        jdbc.sql(CALENDARS).query(JdbcDatasetSource::rules).list().stream()
            .map(rules -> new CalendarHistory(rules, versions.getOrDefault(rules.id(), List.of())))
            .collect(Collectors.toMap(CalendarHistory::id, Function.identity()));
    return new Dataset(release.release(), release.asOf(), calendars);
  }

  private static CalendarRules rules(ResultSet rs, int rowNum) throws SQLException {
    String[] weekend = (String[]) rs.getArray("weekend").getArray();
    Set<DayOfWeek> days =
        Arrays.stream(weekend)
            .map(day -> DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)))
            .collect(Collectors.toSet());
    return new CalendarRules(
        new CalendarId(rs.getString("id")),
        rs.getString("name"),
        CalendarKind.fromCode(rs.getString("kind")),
        ZoneId.of(rs.getString("timezone")),
        days,
        rs.getBoolean("closes_on_holidays"),
        hours(rs));
  }

  private static OperatingHours hours(ResultSet rs) throws SQLException {
    if (!rs.getBoolean("has_hours")) {
      return OperatingHours.UNPUBLISHED;
    }
    String[] names = (String[]) rs.getArray("cutoff_names").getArray();
    String[] deadlines = (String[]) rs.getArray("cutoff_deadlines").getArray();
    List<OperatingHours.Cutoff> cutoffs = new ArrayList<>();
    for (int i = 0; i < names.length; i++) {
      cutoffs.add(new OperatingHours.Cutoff(names[i], LocalTime.parse(deadlines[i])));
    }
    return new OperatingHours.Published(
        rs.getBoolean("always_open"),
        rs.getObject("opens", LocalTime.class),
        rs.getObject("closes", LocalTime.class),
        rs.getBoolean("opens_previous_day"),
        rs.getString("hours_note"),
        cutoffs);
  }

  private static Row row(ResultSet rs, Map<Long, List<SourceRef>> sources) throws SQLException {
    long id = rs.getLong("id");
    OffsetDateTime recordedTo = rs.getObject("recorded_to", OffsetDateTime.class);
    EntryVersion version =
        new EntryVersion(
            rs.getObject("entry_date", LocalDate.class),
            rs.getBoolean("is_open"),
            rs.getString("reason"),
            Basis.fromCode(rs.getString("basis")),
            rs.getLong("revision"),
            instant(rs, "recorded_from"),
            recordedTo == null ? EntryVersion.STILL_CURRENT : recordedTo.toInstant(),
            sources.getOrDefault(id, List.of()));
    return new Row(id, new CalendarId(rs.getString("calendar_id")), version);
  }

  private static Evidence evidence(ResultSet rs, int rowNum) throws SQLException {
    return new Evidence(
        rs.getLong("entry_id"),
        new SourceRef(
            rs.getString("slug"),
            rs.getString("publisher"),
            URI.create(rs.getString("url")),
            HexFormat.of().formatHex(rs.getBytes("sha256")),
            instant(rs, "fetched_at")));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
