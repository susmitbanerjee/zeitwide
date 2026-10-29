package com.settlecal.api.answers.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The calendars of one question, with their coverage, as recorded at one moment. */
record View(List<Covered> calendars, Instant moment, Combine combine) {

  record Covered(CalendarHistory calendar, Coverage coverage) {}

  static View of(Dataset dataset, AnswerQuery query) {
    List<Covered> calendars =
        query.calendars().stream()
            .map(dataset::calendar)
            .map(calendar -> new Covered(calendar, calendar.coverageAt(query.asOf())))
            .toList();
    return new View(calendars, query.asOf(), query.combine());
  }

  /** Every calendar is checked first, so {@code any_open} never answers past a gap. */
  void checkCovered(LocalDate date) {
    for (Covered covered : calendars) {
      if (!covered.coverage().contains(date)) {
        throw new OutOfRangeException(covered.calendar().id(), date);
      }
    }
  }

  DayAnswer answer(LocalDate date) {
    checkCovered(date);
    List<CalendarDay> days =
        calendars.stream().map(covered -> calendarDay(covered.calendar(), date)).toList();
    return new DayAnswer(date, combine.businessDay(days), days);
  }

  private CalendarDay calendarDay(CalendarHistory calendar, LocalDate date) {
    return calendar
        .entryAt(date, moment)
        .<CalendarDay>map(entry -> new CalendarDay.ByEntry(calendar.id(), entry))
        .orElseGet(() -> new CalendarDay.ByWeekendRule(calendar.id(), !calendar.isWeekend(date)));
  }
}
