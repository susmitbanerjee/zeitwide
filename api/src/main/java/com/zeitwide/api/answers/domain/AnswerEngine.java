package com.zeitwide.api.answers.domain;

import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Business-day answers from a dataset. The rules are the dataset's data, never this code.
 *
 * <p>The contract is dataset/README.md; the Python engine in lib-python implements the same one,
 * and the conformance suite checks both. Every surface (REST, MCP, webhooks) answers through this
 * class, so a person and an agent can never get different dates. Pure: no I/O and no clock; the
 * caller says which instant to answer as of.
 */
public final class AnswerEngine {

  /** A search longer than this means the data, not the question, is wrong. */
  static final int MAX_DAYS_SEARCHED = 3660;

  /**
   * Whether {@code date} is a business day.
   *
   * @throws UnknownCalendarException if a calendar isn't in the dataset
   * @throws OutOfRangeException if {@code date} is outside a calendar's coverage
   */
  public DayAnswer isBusinessDay(Dataset dataset, AnswerQuery query, LocalDate date) {
    return View.of(dataset, query).answer(date);
  }

  /** The first business day after {@code date} (never {@code date} itself). */
  public Shifted nextBusinessDay(Dataset dataset, AnswerQuery query, LocalDate date) {
    return addBusinessDays(dataset, query, date, 1);
  }

  /**
   * Moves {@code days} business days from {@code start}, backwards when negative. The start day
   * doesn't count, and 0 returns {@code start}. Every date looked at, {@code start} included, must
   * be within coverage.
   */
  public Shifted addBusinessDays(Dataset dataset, AnswerQuery query, LocalDate start, int days) {
    return shift(View.of(dataset, query), start, days);
  }

  private static Shifted shift(View view, LocalDate start, int days) {
    DayAnswer current = view.answer(start);
    int step = days >= 0 ? 1 : -1;
    int remaining = Math.abs(days);
    List<DayAnswer> skipped = new ArrayList<>();
    int searched = 0;
    while (remaining > 0) {
      searched++;
      if (searched > MAX_DAYS_SEARCHED) {
        throw new IllegalArgumentException(
            "No business day within " + MAX_DAYS_SEARCHED + " days of " + start);
      }
      current = view.answer(current.date().plusDays(step));
      if (current.businessDay()) {
        remaining--;
      } else {
        skipped.add(current);
      }
    }
    return new Shifted(current, skipped);
  }

  /**
   * Every date from {@code from} to {@code to} (both inclusive) that one calendar covers at {@code
   * asOf}, each answered as {@link #isBusinessDay} would. Dates outside coverage are left out,
   * never guessed.
   */
  public List<RangeDay> days(
      Dataset dataset, CalendarId calendarId, LocalDate from, LocalDate to, Instant asOf) {
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("to must not be before from");
    }
    Coverage coverage = dataset.calendar(calendarId).coverageAt(asOf);
    View view = View.of(dataset, new AnswerQuery(List.of(calendarId), Combine.ALL_OPEN, asOf));
    List<RangeDay> days = new ArrayList<>();
    for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
      if (coverage.contains(date)) {
        days.add(
            new RangeDay(view.answer(date), neighbour(view, date, 1), neighbour(view, date, -1)));
      }
    }
    return days;
  }

  /** The nearest business day after (step 1) or before (step -1); null past coverage. */
  private static LocalDate neighbour(View view, LocalDate date, int step) {
    try {
      return shift(view, date, step).date();
    } catch (OutOfRangeException beyondCoverage) {
      return null;
    }
  }

  /**
   * The closures on one calendar from {@code from} to {@code to} (both inclusive) as recorded at
   * {@code asOf}. The whole range must be within coverage, so an empty list means no closures,
   * never no data.
   */
  public List<EntryVersion> closures(
      Dataset dataset, CalendarId calendarId, LocalDate from, LocalDate to, Instant asOf) {
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("to must not be before from");
    }
    CalendarHistory calendar = dataset.calendar(calendarId);
    Coverage coverage = calendar.coverageAt(asOf);
    for (LocalDate end : List.of(from, to)) {
      if (!coverage.contains(end)) {
        throw new OutOfRangeException(calendarId, end);
      }
    }
    return calendar.closuresAt(from, to, asOf);
  }
}
