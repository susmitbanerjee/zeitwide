package com.zeitwide.api.a2a.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuestionParserTest {

  private static final Set<String> IDS =
      Set.of(
          "DE",
          "EU-T2",
          "GB-CHAPS",
          "GB-EAW",
          "US-FEDACH",
          "US-FEDERAL",
          "US-FEDNOW",
          "US-FEDWIRE");
  private final QuestionParser parser = new QuestionParser(IDS);

  @Test
  void shouldReadACheckByTheRailsShortName() {
    assertThat(parser.parseText("Is 2026-12-28 a CHAPS business day?"))
        .isEqualTo(new Question.Check(List.of("GB-CHAPS"), LocalDate.of(2026, 12, 28)));
  }

  @Test
  void shouldReadTPlusNOnSeveralCalendarsInOrder() {
    assertThat(parser.parseText("T+2 from 2026-12-24 on US-FEDWIRE and GB-CHAPS"))
        .isEqualTo(
            new Question.Add(List.of("US-FEDWIRE", "GB-CHAPS"), LocalDate.of(2026, 12, 24), 2));
    assertThat(parser.parseText("T-3 from 2026-01-05 on fedwire"))
        .isEqualTo(new Question.Add(List.of("US-FEDWIRE"), LocalDate.of(2026, 1, 5), -3));
  }

  @Test
  void shouldReadBusinessDaysBeforeAsNegative() {
    assertThat(parser.parseText("5 business days before 2026-07-10 on FedACH"))
        .isEqualTo(new Question.Add(List.of("US-FEDACH"), LocalDate.of(2026, 7, 10), -5));
  }

  @Test
  void shouldReadNextBusinessDay() {
    assertThat(parser.parseText("What is the next business day after 2026-07-03 on US-FEDACH?"))
        .isEqualTo(new Question.Next(List.of("US-FEDACH"), LocalDate.of(2026, 7, 3)));
  }

  @Test
  void shouldReadClosuresInARange() {
    assertThat(parser.parseText("CHAPS closures from 2026-12-01 to 2026-12-31"))
        .isEqualTo(
            new Question.Closures(
                "GB-CHAPS", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)));
  }

  @Test
  void shouldMatchTwoLetterIdsOnlyInCapitals() {
    assertThat(parser.parseText("Is 2026-05-14 a business day in DE?"))
        .isEqualTo(new Question.Check(List.of("DE"), LocalDate.of(2026, 5, 14)));
    assertThatThrownBy(() -> parser.parseText("Is 2026-05-14 a business day de facto?"))
        .isInstanceOf(UnreadableQuestionException.class)
        .hasMessageContaining("Name at least one calendar");
  }

  @Test
  void shouldRefuseRatherThanGuess() {
    assertThatThrownBy(() -> parser.parseText("Is Christmas a CHAPS holiday?"))
        .hasMessageContaining("YYYY-MM-DD");
    assertThatThrownBy(() -> parser.parseText("Is 2026-02-30 a CHAPS business day?"))
        .hasMessageContaining("not a valid date");
    assertThatThrownBy(() -> parser.parseText("T+2000 from 2026-01-05 on CHAPS"))
        .hasMessageContaining("at most 1000");
    assertThatThrownBy(
            () -> parser.parseText("Closures from 2026-01-01 to 2026-02-01 on CHAPS and T2"))
        .hasMessageContaining("one calendar");
    assertThatThrownBy(() -> parser.parseText(" ")).isInstanceOf(UnreadableQuestionException.class);
    assertThatThrownBy(() -> parser.parseText("x".repeat(QuestionParser.MAX_TEXT_LENGTH + 1)))
        .hasMessageContaining("longer than");
  }

  @Test
  void shouldReadJsonWithTheToolArgumentNames() {
    assertThat(
            parser.parseData(
                Map.of(
                    "operation",
                    "add_business_days",
                    "calendars",
                    List.of("GB-CHAPS"),
                    "date",
                    "2026-12-24",
                    "days",
                    1)))
        .isEqualTo(new Question.Add(List.of("GB-CHAPS"), LocalDate.of(2026, 12, 24), 1));
    assertThat(
            parser.parseData(
                Map.of(
                    "operation",
                    "next_business_day",
                    "calendars",
                    List.of("EU-T2"),
                    "date",
                    "2026-04-02")))
        .isEqualTo(new Question.Next(List.of("EU-T2"), LocalDate.of(2026, 4, 2)));
  }

  @Test
  void shouldRefuseJsonItCannotAnswer() {
    assertThatThrownBy(() -> parser.parseData(Map.of("date", "2026-01-01")))
        .hasMessageContaining("operation");
    assertThatThrownBy(
            () ->
                parser.parseData(
                    Map.of(
                        "operation",
                        "is_business_day",
                        "calendars",
                        List.of("XX-NOPE"),
                        "date",
                        "2026-01-01")))
        .hasMessageContaining("Unknown calendar");
    assertThatThrownBy(
            () ->
                parser.parseData(
                    Map.of(
                        "operation",
                        "add_business_days",
                        "calendars",
                        List.of("GB-CHAPS"),
                        "date",
                        "2026-01-01",
                        "days",
                        1.5)))
        .hasMessageContaining("whole number");
  }
}
