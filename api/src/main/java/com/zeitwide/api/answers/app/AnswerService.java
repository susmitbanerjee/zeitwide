package com.zeitwide.api.answers.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zeitwide.api.answers.domain.AnswerEngine;
import com.zeitwide.api.answers.domain.AnswerQuery;
import com.zeitwide.api.answers.domain.CalendarHistory;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.answers.domain.Dataset;
import com.zeitwide.api.answers.domain.DatasetRelease;
import com.zeitwide.api.answers.domain.DayAnswer;
import com.zeitwide.api.answers.domain.EntryVersion;
import com.zeitwide.api.answers.domain.Shifted;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.web.RequestValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * The answers use cases. Every answer comes from the {@link AnswerEngine} and the latest dataset
 * release, and is cached by (request, release): a release never changes, so neither does an answer
 * from it.
 *
 * <p>No transaction here: a cached answer needs no database connection.
 *
 * <p>Counts answers by operation and calendar. Calendar IDs are safe labels: an unknown one fails
 * before it is counted, so the label set is the dataset's calendars.
 */
@Service
public class AnswerService {

  /** The default window ends this many days after today. */
  public static final int DEFAULT_DAYS_AHEAD = 90;

  /** Covers the default window (up to 456 days) with room to spare. */
  public static final int MAX_RANGE_DAYS = 500;

  static final String ANSWERS_METRIC = "zeitwide.answers";
  static final String CACHE_NAME = "answers";

  /** The calendar label of an answer about every calendar, such as the calendar list. */
  static final String ALL_CALENDARS = "all";

  private final AnswerEngine engine;
  private final CurrentDataset current;
  private final Clock clock;
  private final Cache<AnswerKey, Answered<?>> cache;
  private final MeterRegistry meters;

  AnswerService(
      AnswerEngine engine,
      CurrentDataset current,
      Clock clock,
      AnswerProperties properties,
      MeterRegistry meters) {
    this.engine = engine;
    this.current = current;
    this.clock = clock;
    this.meters = meters;
    this.cache =
        Caffeine.newBuilder().maximumSize(properties.cacheMaxEntries()).recordStats().build();
    CaffeineCacheMetrics.monitor(meters, cache, CACHE_NAME);
  }

  /** The request, with as_of already capped at the release's, plus the release itself. */
  private record AnswerKey(String operation, List<Object> request, DatasetRelease release) {}

  public Answered<List<CalendarSummary>> calendars(AsOf asOf) {
    return answer(
        "calendars",
        List.of(),
        List.of(),
        asOf,
        (dataset, at) ->
            dataset.calendars().values().stream()
                .sorted((a, b) -> a.id().value().compareTo(b.id().value()))
                .map(calendar -> summary(calendar, at))
                .toList(),
        dataset -> dataset.calendars().keySet());
  }

  public Answered<DayAnswer> check(
      List<CalendarId> calendars, Combine combine, LocalDate date, AsOf asOf) {
    return answer(
        "check",
        calendars,
        List.of(calendars, combine, date),
        asOf,
        (dataset, at) ->
            engine.isBusinessDay(dataset, new AnswerQuery(calendars, combine, at), date),
        dataset -> calendars);
  }

  public Answered<Shifted> add(
      List<CalendarId> calendars, Combine combine, LocalDate date, int days, AsOf asOf) {
    return answer(
        "add",
        calendars,
        List.of(calendars, combine, date, days),
        asOf,
        (dataset, at) ->
            engine.addBusinessDays(dataset, new AnswerQuery(calendars, combine, at), date, days),
        dataset -> calendars);
  }

  public Answered<Shifted> next(
      List<CalendarId> calendars, Combine combine, LocalDate date, AsOf asOf) {
    return answer(
        "next",
        calendars,
        List.of(calendars, combine, date),
        asOf,
        (dataset, at) ->
            engine.nextBusinessDay(dataset, new AnswerQuery(calendars, combine, at), date),
        dataset -> calendars);
  }

  public Answered<List<EntryVersion>> closures(
      CalendarId calendar, LocalDate from, LocalDate to, AsOf asOf) {
    return answer(
        "closures",
        List.of(calendar),
        List.of(calendar, from, to),
        asOf,
        (dataset, at) -> engine.closures(dataset, calendar, from, to, at),
        dataset -> List.of(calendar));
  }

  /**
   * One calendar's days from {@code from} to {@code to}. Either may be null: {@code from} then
   * defaults to 1 January of the current year and {@code to} to {@value #DEFAULT_DAYS_AHEAD} days
   * from today, both in the calendar's time zone.
   */
  public Answered<DayRange> days(CalendarId calendar, LocalDate from, LocalDate to, AsOf asOf) {
    ZoneId zone = current.get().calendar(calendar).rules().timezone();
    LocalDate today = LocalDate.now(clock.withZone(zone));
    LocalDate start = from == null ? today.withDayOfYear(1) : from;
    LocalDate end = to == null ? today.plusDays(DEFAULT_DAYS_AHEAD) : to;
    if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
      throw new RequestValidationException(
          "to must be on or after from, at most " + MAX_RANGE_DAYS + " days in all");
    }
    return answer(
        "days",
        List.of(calendar),
        List.of(calendar, start, end, today),
        asOf,
        (dataset, at) ->
            new DayRange(today, start, end, engine.days(dataset, calendar, start, end, at)),
        dataset -> List.of(calendar));
  }

  private interface Question<T> {
    T ask(Dataset dataset, Instant asOf);
  }

  // Unchecked: each key's operation always caches the same answer type.
  @SuppressWarnings("unchecked")
  private <T> Answered<T> answer(
      String operation,
      List<CalendarId> asked,
      List<Object> request,
      AsOf asOf,
      Question<T> question,
      Function<Dataset, Collection<CalendarId>> calendarsUsed) {
    Dataset dataset = current.get();
    Instant at = cappedAsOf(asOf, dataset);
    AnswerKey key = new AnswerKey(operation, List.of(request, at), dataset.release());
    Answered<T> answered =
        (Answered<T>)
            cache.get(
                key,
                k ->
                    new Answered<>(
                        question.ask(dataset, at),
                        dataset.release(),
                        at,
                        revisions(dataset, calendarsUsed.apply(dataset), at)));
    countAnswer(operation, asked);
    return answered;
  }

  private void countAnswer(String operation, List<CalendarId> asked) {
    if (asked.isEmpty()) {
      answersCounter(operation, ALL_CALENDARS).increment();
      return;
    }
    asked.forEach(calendar -> answersCounter(operation, calendar.value()).increment());
  }

  private Counter answersCounter(String operation, String calendar) {
    return Counter.builder(ANSWERS_METRIC)
        .description("Answers served, by operation and calendar")
        .tag("operation", operation)
        .tag("calendar", calendar)
        .register(meters);
  }

  // A release holds what was recorded up to its as_of; a later instant would claim more.
  private Instant cappedAsOf(AsOf asOf, Dataset dataset) {
    Instant requested = asOf.resolve(clock);
    return requested.isAfter(dataset.asOf()) ? dataset.asOf() : requested;
  }

  private static List<CalendarRevision> revisions(
      Dataset dataset, Collection<CalendarId> calendars, Instant at) {
    return calendars.stream()
        .sorted((a, b) -> a.value().compareTo(b.value()))
        .map(id -> new CalendarRevision(id, dataset.calendar(id).revisionAt(at)))
        .toList();
  }

  private static CalendarSummary summary(CalendarHistory calendar, Instant at) {
    return new CalendarSummary(
        calendar.rules(),
        calendar.revisionAt(at),
        calendar.revisedAt(at).orElse(null),
        calendar.coverageAt(at));
  }
}
