package com.settlecal.api.answers;

import com.settlecal.api.answers.domain.CalendarHistory;
import com.settlecal.api.answers.domain.CalendarRules;
import com.settlecal.api.answers.domain.ConformanceRunner;
import com.settlecal.api.answers.domain.Dataset;
import com.settlecal.api.answers.domain.EntryVersion;
import com.settlecal.api.answers.domain.SourceRef;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes the conformance suite's golden dataset into Postgres as the pipeline would have:
 * calendars, sources, snapshots, every entry version with its recorded time, and a dataset release.
 */
public final class GoldenDatabase {

  public static final Dataset GOLDEN = ConformanceRunner.goldenDataset();
  private static final int HTTP_OK = 200;

  private final JdbcClient jdbc;
  private final Map<String, Long> sourceIds = new HashMap<>();
  private final Map<String, Long> snapshotIds = new HashMap<>();

  private GoldenDatabase(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Loads it once per database; later calls find the release and do nothing. */
  public static void load(JdbcClient jdbc) {
    boolean loaded =
        jdbc.sql("SELECT count(*) FROM dataset_release WHERE id = :id")
                .param("id", GOLDEN.release().value())
                .query(Long.class)
                .single()
            > 0;
    if (!loaded) {
      new GoldenDatabase(jdbc).write();
    }
  }

  private void write() {
    for (CalendarHistory calendar : GOLDEN.calendars().values()) {
      insertCalendar(calendar);
    }
    for (CalendarHistory calendar : GOLDEN.calendars().values()) {
      for (EntryVersion version : calendar.versions()) {
        insertEntry(calendar, version);
      }
    }
    jdbc.sql(
            """
            INSERT INTO dataset_release (id, as_of, calendar_revisions, export_sha256)
            VALUES (:id, :asOf, '{}'::jsonb, decode(repeat('00', 32), 'hex'))
            """)
        .param("id", GOLDEN.release().value())
        .param("asOf", GOLDEN.asOf().atOffset(ZoneOffset.UTC))
        .update();
  }

  private void insertCalendar(CalendarHistory calendar) {
    CalendarRules rules = calendar.rules();
    String weekend =
        rules.weekend().stream()
            .sorted()
            .map(day -> "\"" + day.name().toLowerCase(Locale.ROOT) + "\"")
            .collect(Collectors.joining(",", "{\"days\": [", "]}"));
    jdbc.sql(
            """
            INSERT INTO calendar (id, name, kind, timezone, weekend_rule, closes_on_holidays,
                                  current_revision)
            VALUES (:id, :name, :kind, :timezone, CAST(:weekend AS jsonb), :closes, :revision)
            """)
        .param("id", rules.id().value())
        .param("name", rules.name())
        .param("kind", rules.kind().code())
        .param("timezone", rules.timezone().getId())
        .param("weekend", weekend)
        .param("closes", rules.closesOnHolidays())
        .param("revision", calendar.revisionAt(GOLDEN.asOf()))
        .update();
  }

  private void insertEntry(CalendarHistory calendar, EntryVersion version) {
    long entryId =
        jdbc.sql(
                """
                INSERT INTO calendar_entry (calendar_id, entry_date, is_open, reason, basis,
                                            revision, recorded_during)
                VALUES (:calendar, :date, :open, :reason, :basis, :revision,
                        tstzrange(:from, :to, '[)'))
                RETURNING id
                """)
            .param("calendar", calendar.id().value())
            .param("date", version.date())
            .param("open", version.open())
            .param("reason", version.reason())
            .param("basis", version.basis().code())
            .param("revision", version.revision())
            .param("from", version.recordedFrom().atOffset(ZoneOffset.UTC))
            .param("to", version.isCurrent() ? null : version.recordedTo().atOffset(ZoneOffset.UTC))
            .query(Long.class)
            .single();
    for (SourceRef source : version.sources()) {
      jdbc.sql("INSERT INTO entry_source (entry_id, snapshot_id) VALUES (:entry, :snapshot)")
          .param("entry", entryId)
          .param("snapshot", snapshot(source))
          .update();
    }
  }

  private long snapshot(SourceRef source) {
    long sourceId = sourceIds.computeIfAbsent(source.sourceId(), id -> insertSource(source));
    return snapshotIds.computeIfAbsent(
        source.sha256(),
        sha ->
            jdbc.sql(
                    """
                    INSERT INTO source_snapshot (source_id, fetched_at, sha256, object_key,
                                                 http_status, byte_size)
                    VALUES (:source, :fetched, decode(:sha, 'hex'), :key, :status, 0)
                    RETURNING id
                    """)
                .param("source", sourceId)
                .param("fetched", source.fetchedAt().atOffset(ZoneOffset.UTC))
                .param("sha", sha)
                .param("key", "snapshots/" + sha)
                .param("status", HTTP_OK)
                .query(Long.class)
                .single());
  }

  private long insertSource(SourceRef source) {
    return jdbc.sql(
            """
            INSERT INTO source (slug, publisher, url, parser_name, schedule, role)
            VALUES (:slug, :publisher, :url, :slug, 'daily', 'primary')
            RETURNING id
            """)
        .param("slug", source.sourceId())
        .param("publisher", source.publisher())
        .param("url", source.url().toString())
        .query(Long.class)
        .single();
  }
}
