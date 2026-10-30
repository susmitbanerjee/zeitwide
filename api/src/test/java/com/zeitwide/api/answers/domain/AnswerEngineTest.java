package com.zeitwide.api.answers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeitwide.api.calendars.domain.Basis;
import com.zeitwide.api.calendars.domain.CalendarId;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnswerEngineTest {

  static final CalendarId FEDWIRE = new CalendarId("US-FEDWIRE");
  static final CalendarId FEDNOW = new CalendarId("US-FEDNOW");
  static final CalendarId T2 = new CalendarId("EU-T2");
  static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);
  static final Instant RECORDED = Instant.parse("2026-01-01T00:00:00Z");
  // A made-up correction, to test history: Thanksgiving first recorded as the 27th.
  static final Instant CORRECTED = Instant.parse("2026-03-01T00:00:00Z");
  static final Instant AS_OF = Instant.parse("2026-10-28T00:00:00Z");
  static final Set<DayOfWeek> SATURDAY_AND_SUNDAY = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

  static final Dataset DATASET =
      new Dataset(
          new DatasetRelease("2026.10.28.1"),
          AS_OF,
          Map.of(
              FEDWIRE,
              new CalendarHistory(
                  rules(FEDWIRE, SATURDAY_AND_SUNDAY, true),
                  List.of(
                      closure(LocalDate.of(2026, 1, 1), RECORDED, EntryVersion.STILL_CURRENT),
                      closure(LocalDate.of(2026, 11, 27), RECORDED, CORRECTED),
                      closure(THANKSGIVING, CORRECTED, EntryVersion.STILL_CURRENT),
                      closure(LocalDate.of(2027, 12, 24), RECORDED, EntryVersion.STILL_CURRENT))),
              FEDNOW,
              new CalendarHistory(rules(FEDNOW, Set.of(), false), List.of()),
              T2,
              new CalendarHistory(
                  rules(T2, SATURDAY_AND_SUNDAY, true),
                  List.of(
                      closure(LocalDate.of(2026, 4, 3), RECORDED, EntryVersion.STILL_CURRENT)))));

  private final AnswerEngine engine = new AnswerEngine();

  @Test
  void shouldCloseAWeekdayWithAnEntry() {
    DayAnswer answer = engine.isBusinessDay(DATASET, query(FEDWIRE), THANKSGIVING);

    assertThat(answer.businessDay()).isFalse();
    assertThat(answer.calendars())
        .singleElement()
        .isInstanceOfSatisfying(
            CalendarDay.ByEntry.class,
            day -> assertThat(day.entry().sources()).extracting(SourceRef::sha256).isNotEmpty());
  }

  @Test
  void shouldCloseTheWeekendUnlessTheCalendarHasNone() {
    LocalDate saturday = LocalDate.of(2026, 11, 28);

    assertThat(engine.isBusinessDay(DATASET, query(FEDWIRE), saturday).calendars())
        .containsExactly(new CalendarDay.ByWeekendRule(FEDWIRE, false));
    assertThat(engine.isBusinessDay(DATASET, query(FEDNOW), saturday).businessDay()).isTrue();
  }

  @Test
  void shouldCombineCalendarsWithAllOpenOrAnyOpen() {
    LocalDate goodFriday = LocalDate.of(2026, 4, 3);
    List<CalendarId> both = List.of(FEDWIRE, T2);

    assertThat(
            engine.isBusinessDay(DATASET, query(both, Combine.ALL_OPEN), goodFriday).businessDay())
        .isFalse();
    assertThat(
            engine.isBusinessDay(DATASET, query(both, Combine.ANY_OPEN), goodFriday).businessDay())
        .isTrue();
  }

  @Test
  void shouldAnswerWithTheVersionRecordedAtAsOf() {
    AnswerQuery before =
        new AnswerQuery(List.of(FEDWIRE), Combine.ALL_OPEN, Instant.parse("2026-02-01T00:00:00Z"));

    assertThat(engine.isBusinessDay(DATASET, before, THANKSGIVING).businessDay()).isTrue();
    assertThat(engine.isBusinessDay(DATASET, before, LocalDate.of(2026, 11, 27)).businessDay())
        .isFalse();
    assertThat(
            engine.isBusinessDay(DATASET, query(FEDWIRE), LocalDate.of(2026, 11, 27)).businessDay())
        .isTrue();
  }

  @Test
  void shouldEndAReplacedVersionWhereTheNextBegins() {
    AnswerQuery atCorrection = new AnswerQuery(List.of(FEDWIRE), Combine.ALL_OPEN, CORRECTED);
    AnswerQuery justBefore =
        new AnswerQuery(List.of(FEDWIRE), Combine.ALL_OPEN, CORRECTED.minusNanos(1));

    assertThat(engine.isBusinessDay(DATASET, atCorrection, THANKSGIVING).businessDay()).isFalse();
    assertThat(engine.isBusinessDay(DATASET, justBefore, LocalDate.of(2026, 11, 27)).businessDay())
        .isFalse();
  }

  @Test
  void shouldRejectDatesOutsideTheRecordedYears() {
    LocalDate after = LocalDate.of(2028, 1, 3);

    assertThatThrownBy(() -> engine.isBusinessDay(DATASET, query(FEDWIRE), after))
        .isInstanceOfSatisfying(
            OutOfRangeException.class,
            e -> {
              assertThat(e.code()).isEqualTo("out_of_range");
              assertThat(e.calendarId()).isEqualTo(FEDWIRE);
              assertThat(e.date()).isEqualTo(after);
            });
    assertThat(
            engine.isBusinessDay(DATASET, query(FEDWIRE), LocalDate.of(2027, 12, 31)).businessDay())
        .isTrue();
    assertThatThrownBy(
            () -> engine.isBusinessDay(DATASET, query(FEDWIRE), LocalDate.of(2025, 12, 31)))
        .isInstanceOf(OutOfRangeException.class);
  }

  @Test
  void shouldBeOutOfRangeBeforeAnythingWasRecorded() {
    AnswerQuery early =
        new AnswerQuery(List.of(T2), Combine.ALL_OPEN, Instant.parse("2025-01-01T00:00:00Z"));

    assertThatThrownBy(() -> engine.isBusinessDay(DATASET, early, LocalDate.of(2026, 4, 3)))
        .isInstanceOf(OutOfRangeException.class);
  }

  @Test
  void shouldNotAnswerAnyOpenPastAGap() {
    AnswerQuery anyOpen = query(List.of(FEDNOW, FEDWIRE), Combine.ANY_OPEN);

    assertThatThrownBy(() -> engine.isBusinessDay(DATASET, anyOpen, LocalDate.of(2099, 1, 1)))
        .isInstanceOf(OutOfRangeException.class);
  }

  @Test
  void shouldGiveCalendarsThatNeverCloseNoRange() {
    assertThat(engine.isBusinessDay(DATASET, query(FEDNOW), LocalDate.of(2099, 1, 1)).businessDay())
        .isTrue();
  }

  @Test
  void shouldReportTheDaysSkippedWhenAdding() {
    Shifted shifted =
        engine.addBusinessDays(DATASET, query(FEDWIRE), LocalDate.of(2026, 11, 25), 2);

    assertThat(shifted.date()).isEqualTo(LocalDate.of(2026, 11, 30));
    assertThat(shifted.skippedDates())
        .containsExactly(THANKSGIVING, LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 29));
    assertThat(shifted.skipped().getFirst().calendars())
        .singleElement()
        .isInstanceOf(CalendarDay.ByEntry.class);
    assertThat(shifted.landed().businessDay()).isTrue();
  }

  @Test
  void shouldWalkBackwardsWhenSubtracting() {
    Shifted shifted =
        engine.addBusinessDays(DATASET, query(FEDWIRE), LocalDate.of(2026, 11, 30), -1);

    assertThat(shifted.date()).isEqualTo(LocalDate.of(2026, 11, 27));
  }

  @Test
  void shouldReturnTheStartWhenAddingZero() {
    Shifted shifted = engine.addBusinessDays(DATASET, query(FEDWIRE), THANKSGIVING, 0);

    assertThat(shifted.date()).isEqualTo(THANKSGIVING);
    assertThat(shifted.skipped()).isEmpty();
    assertThat(shifted.landed().businessDay()).isFalse();
  }

  @Test
  void shouldCheckTheStartIsInRangeWhenAdding() {
    assertThatThrownBy(
            () -> engine.addBusinessDays(DATASET, query(FEDWIRE), LocalDate.of(2099, 1, 1), 0))
        .isInstanceOf(OutOfRangeException.class);
  }

  @Test
  void shouldBeOutOfRangeWhenAddingPastTheEndOfCoverage() {
    assertThatThrownBy(
            () -> engine.addBusinessDays(DATASET, query(FEDWIRE), LocalDate.of(2027, 12, 30), 2))
        .isInstanceOf(OutOfRangeException.class);
  }

  @Test
  void shouldStopSearchingAfterTenYears() {
    Dataset alwaysClosed =
        new Dataset(
            DATASET.release(),
            AS_OF,
            Map.of(
                FEDNOW,
                new CalendarHistory(rules(FEDNOW, Set.of(DayOfWeek.values()), false), List.of())));

    assertThatThrownBy(() -> engine.addBusinessDays(alwaysClosed, query(FEDNOW), THANKSGIVING, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("3660");
  }

  @Test
  void shouldNeverReturnTheDayItselfAsTheNextBusinessDay() {
    Shifted next = engine.nextBusinessDay(DATASET, query(FEDWIRE), LocalDate.of(2026, 11, 25));

    assertThat(next.date()).isEqualTo(LocalDate.of(2026, 11, 27));
    assertThat(next.skippedDates()).containsExactly(THANKSGIVING);
  }

  @Test
  void shouldRejectAnUnknownCalendar() {
    CalendarId unknown = new CalendarId("XX-NONE");

    assertThatThrownBy(() -> engine.isBusinessDay(DATASET, query(unknown), THANKSGIVING))
        .isInstanceOfSatisfying(
            UnknownCalendarException.class,
            e -> {
              assertThat(e.code()).isEqualTo("unknown_calendar");
              assertThat(e.calendarId()).isEqualTo(unknown);
            });
  }

  @Test
  void shouldListClosuresRecordedAtAsOfInDateOrder() {
    List<EntryVersion> closures =
        engine.closures(
            DATASET, FEDWIRE, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), AS_OF);

    assertThat(closures)
        .extracting(EntryVersion::date)
        .containsExactly(LocalDate.of(2026, 1, 1), THANKSGIVING, LocalDate.of(2027, 12, 24));
  }

  @Test
  void shouldNotListClosuresBeyondCoverage() {
    assertThatThrownBy(
            () ->
                engine.closures(
                    DATASET, FEDWIRE, LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 31), AS_OF))
        .isInstanceOf(OutOfRangeException.class);
    assertThatThrownBy(
            () ->
                engine.closures(
                    DATASET, FEDWIRE, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 1), AS_OF))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldReportTheRevisionPublishedByAsOf() {
    CalendarHistory fedwire = DATASET.calendar(FEDWIRE);

    assertThat(fedwire.revisionAt(RECORDED.minusSeconds(1))).isZero();
    assertThat(fedwire.revisionAt(AS_OF)).isEqualTo(1);
  }

  static CalendarRules rules(CalendarId id, Set<DayOfWeek> weekend, boolean closesOnHolidays) {
    return new CalendarRules(
        id,
        id.value(),
        CalendarKind.RAIL,
        ZoneId.of("America/New_York"),
        weekend,
        closesOnHolidays);
  }

  static AnswerQuery query(CalendarId calendar) {
    return query(List.of(calendar), Combine.ALL_OPEN);
  }

  static AnswerQuery query(List<CalendarId> calendars, Combine combine) {
    return new AnswerQuery(calendars, combine, AS_OF);
  }

  static EntryVersion closure(LocalDate date, Instant from, Instant to) {
    return new EntryVersion(
        date,
        false,
        "Holiday",
        Basis.PUBLISHED,
        1,
        from,
        to,
        List.of(
            new SourceRef(
                "test",
                "Test publisher",
                URI.create("https://example.com/holidays"),
                "0".repeat(64),
                RECORDED)));
  }
}
