package com.zeitwide.api.answers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Every shared conformance case must pass against the golden dataset. */
class ConformanceTest {

  private static final Dataset GOLDEN = ConformanceRunner.goldenDataset();

  private final ConformanceRunner runner = new ConformanceRunner();

  @TestFactory
  Stream<DynamicTest> shouldPassEveryConformanceCase() {
    return ConformanceRunner.casePaths().stream()
        .map(path -> DynamicTest.dynamicTest(name(path), () -> assertPasses(path)));
  }

  @Test
  void shouldFindTheCases() {
    assertThat(ConformanceRunner.casePaths()).isNotEmpty();
  }

  @Test
  void shouldFailACaseWithTheWrongAnswer() throws Exception {
    JsonNode wrong =
        new ObjectMapper()
            .readTree(
                """
                {"id": "wrong", "dataset_release": "2026.10.29.0",
                 "input": {"operation": "is_business_day", "calendars": ["US-FEDWIRE"],
                           "date": "2026-11-26"},
                 "expected": {"is_business_day": true}}
                """);

    ConformanceRunner.CaseResult result = runner.run(GOLDEN, wrong);

    assertThat(result.passed()).isFalse();
    assertThat(result.detail()).contains("\"is_business_day\":false");
  }

  @Test
  void shouldReportAnExpectedErrorAsTheAnswer() throws Exception {
    JsonNode unknown =
        new ObjectMapper()
            .readTree(
                """
                {"id": "unknown", "dataset_release": "2026.10.29.0",
                 "input": {"operation": "next_business_day", "calendars": ["XX-NONE"],
                           "date": "2026-11-26"},
                 "expected": {"error": "unknown_calendar"}}
                """);

    assertThat(runner.run(GOLDEN, unknown).passed()).isTrue();
  }

  private void assertPasses(Path path) {
    ConformanceRunner.CaseResult result = runner.run(GOLDEN, ConformanceRunner.readCase(path));

    assertThat(result.passed()).as(result.detail()).isTrue();
  }

  private static String name(Path path) {
    return path.getFileName().toString().replace(".json", "");
  }
}
