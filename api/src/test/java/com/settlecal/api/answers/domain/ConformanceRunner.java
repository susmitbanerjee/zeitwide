package com.settlecal.api.answers.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.settlecal.api.calendars.domain.CalendarId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the shared conformance cases (conformance/cases/) through the {@link AnswerEngine}. It only
 * loads cases, asks the engine and compares: the rules are never encoded here.
 */
public final class ConformanceRunner {

  /** Gradle runs tests from api/; build.gradle.kts declares this folder as a test input. */
  public static final Path CONFORMANCE = Path.of("..", "conformance");

  private static final ObjectMapper JSON = new ObjectMapper();

  private final AnswerEngine engine = new AnswerEngine();

  public record CaseResult(String id, boolean passed, String detail) {}

  public static Dataset goldenDataset() {
    return ReleaseFile.read(CONFORMANCE.resolve("dataset.json"));
  }

  public static List<Path> casePaths() {
    try (Stream<Path> paths = Files.list(CONFORMANCE.resolve("cases"))) {
      return paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Can't list the conformance cases", e);
    }
  }

  public static JsonNode readCase(Path path) {
    try {
      return JSON.readTree(path.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read " + path, e);
    }
  }

  public CaseResult run(Dataset dataset, JsonNode testCase) {
    JsonNode expected = testCase.path("expected");
    JsonNode actual = answer(dataset, testCase.path("input"));
    String id = testCase.path("id").asText();
    if (actual.equals(expected)) {
      return new CaseResult(id, true, "");
    }
    return new CaseResult(id, false, "expected " + expected + ", got " + actual);
  }

  private JsonNode answer(Dataset dataset, JsonNode input) {
    AnswerQuery query = query(dataset, input);
    LocalDate date = LocalDate.parse(input.path("date").asText());
    ObjectNode answer = JSON.createObjectNode();
    try {
      switch (input.path("operation").asText()) {
        case "is_business_day" ->
            answer.put("is_business_day", engine.isBusinessDay(dataset, query, date).businessDay());
        case "next_business_day" -> shifted(answer, engine.nextBusinessDay(dataset, query, date));
        case "add_business_days" ->
            shifted(
                answer,
                engine.addBusinessDays(dataset, query, date, input.path("days").intValue()));
        default -> throw new IllegalArgumentException("Unknown operation in a case");
      }
    } catch (AnswerException e) {
      answer.removeAll().put("error", e.code());
    }
    return answer;
  }

  private static AnswerQuery query(Dataset dataset, JsonNode input) {
    List<CalendarId> calendars =
        ReleaseFile.list(input.path("calendars")).stream()
            .map(calendar -> new CalendarId(calendar.asText()))
            .toList();
    Combine combine = Combine.fromCode(input.path("combine").asText(Combine.ALL_OPEN.code()));
    JsonNode asOf = input.path("as_of");
    return new AnswerQuery(
        calendars,
        combine,
        asOf.isMissingNode() ? dataset.asOf() : ReleaseFile.instant(asOf.asText()));
  }

  private static void shifted(ObjectNode answer, Shifted shifted) {
    answer.put("date", shifted.date().toString());
    ArrayNode skipped = answer.putArray("skipped");
    shifted.skippedDates().forEach(day -> skipped.add(day.toString()));
  }
}
