package com.settlecal.api.answers.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlecal.api.calendars.domain.Basis;
import com.settlecal.api.calendars.domain.CalendarId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Reads a dataset release file (dataset/schema/release.schema.json) into a {@link Dataset}. */
public final class ReleaseFile {

  private static final int SCHEMA_VERSION = 2;
  private static final ObjectMapper JSON = new ObjectMapper();

  private ReleaseFile() {}

  public static Dataset read(Path path) {
    try {
      return parse(JSON.readTree(path.toFile()));
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read " + path, e);
    }
  }

  public static Dataset parse(JsonNode release) {
    if (release.path("schema_version").asInt() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Expected schema_version " + SCHEMA_VERSION);
    }
    Map<CalendarId, CalendarHistory> calendars =
        stream(release.path("calendars"))
            .map(ReleaseFile::calendar)
            .collect(Collectors.toMap(CalendarHistory::id, Function.identity()));
    return new Dataset(
        new DatasetRelease(release.path("release").asText()),
        instant(release.path("as_of").asText()),
        calendars);
  }

  public static Instant instant(String text) {
    return OffsetDateTime.parse(text).toInstant();
  }

  private static CalendarHistory calendar(JsonNode calendar) {
    Set<DayOfWeek> weekend =
        stream(calendar.path("weekend"))
            .map(day -> DayOfWeek.valueOf(day.asText().toUpperCase(Locale.ROOT)))
            .collect(Collectors.toSet());
    CalendarRules rules =
        new CalendarRules(
            new CalendarId(calendar.path("id").asText()),
            calendar.path("name").asText(),
            CalendarKind.fromCode(calendar.path("kind").asText()),
            ZoneId.of(calendar.path("timezone").asText()),
            weekend,
            calendar.path("closes_on_holidays").asBoolean());
    return new CalendarHistory(
        rules, stream(calendar.path("entries")).map(ReleaseFile::entry).toList());
  }

  private static EntryVersion entry(JsonNode entry) {
    JsonNode recordedTo = entry.path("recorded_to");
    return new EntryVersion(
        LocalDate.parse(entry.path("date").asText()),
        entry.path("is_open").asBoolean(),
        entry.path("reason").asText(),
        Basis.fromCode(entry.path("basis").asText()),
        entry.path("revision").asLong(),
        instant(entry.path("recorded_from").asText()),
        recordedTo.isNull() ? EntryVersion.STILL_CURRENT : instant(recordedTo.asText()),
        stream(entry.path("sources")).map(ReleaseFile::source).toList());
  }

  private static SourceRef source(JsonNode source) {
    return new SourceRef(
        source.path("source_id").asText(),
        source.path("publisher").asText(),
        URI.create(source.path("url").asText()),
        source.path("sha256").asText(),
        instant(source.path("fetched_at").asText()));
  }

  private static Stream<JsonNode> stream(JsonNode array) {
    if (!array.isArray()) {
      throw new IllegalArgumentException("Expected a JSON array");
    }
    return StreamSupport.stream(array.spliterator(), false);
  }

  static List<JsonNode> list(JsonNode array) {
    return stream(array).toList();
  }
}
