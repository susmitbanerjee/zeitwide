package com.zeitwide.api.answers.domain;

import static com.zeitwide.api.answers.domain.AnswerEngineTest.DATASET;
import static com.zeitwide.api.answers.domain.AnswerEngineTest.FEDWIRE;
import static com.zeitwide.api.answers.domain.AnswerEngineTest.query;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class AnswerEnginePropertiesTest {

  private static final AnswerQuery FEDWIRE_ONLY = query(FEDWIRE);
  // Kept away from the ends of the test dataset's coverage (2026 and 2027).
  private static final LocalDate EARLIEST = LocalDate.of(2026, 2, 1);
  private static final LocalDate LATEST = LocalDate.of(2027, 12, 1);
  private static final int MAX_DAYS = 20;

  private final AnswerEngine engine = new AnswerEngine();

  @Property
  void shouldLandOnABusinessDayAfterTheStart(
      @ForAll("starts") LocalDate start, @ForAll @IntRange(min = 1, max = MAX_DAYS) int days) {
    Shifted shifted = engine.addBusinessDays(DATASET, FEDWIRE_ONLY, start, days);

    assertThat(shifted.date()).isAfter(start);
    assertThat(isBusinessDay(shifted.date())).isTrue();
    assertThat(shifted.skippedDates()).noneMatch(this::isBusinessDay);
    assertThat(ChronoUnit.DAYS.between(start, shifted.date()))
        .isEqualTo(days + shifted.skipped().size());
  }

  @Property
  void shouldReturnOnOrBeforeTheStartAfterAddingThenSubtracting(
      @ForAll("starts") LocalDate start, @ForAll @IntRange(min = 1, max = MAX_DAYS) int days) {
    LocalDate there = engine.addBusinessDays(DATASET, FEDWIRE_ONLY, start, days).date();
    LocalDate back = engine.addBusinessDays(DATASET, FEDWIRE_ONLY, there, -days).date();

    assertThat(back).isBeforeOrEqualTo(start);
    assertThat(isBusinessDay(back)).isTrue();
  }

  @Property
  void shouldMakeTheNextBusinessDayTheSameAsAddingOne(@ForAll("starts") LocalDate start) {
    assertThat(engine.nextBusinessDay(DATASET, FEDWIRE_ONLY, start))
        .isEqualTo(engine.addBusinessDays(DATASET, FEDWIRE_ONLY, start, 1));
  }

  @Provide
  Arbitrary<LocalDate> starts() {
    return Arbitraries.longs()
        .between(0, ChronoUnit.DAYS.between(EARLIEST, LATEST))
        .map(EARLIEST::plusDays);
  }

  private boolean isBusinessDay(LocalDate date) {
    return engine.isBusinessDay(DATASET, FEDWIRE_ONLY, date).businessDay();
  }
}
