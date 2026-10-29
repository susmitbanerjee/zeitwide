package com.settlecal.api.answers.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.settlecal.api.answers.domain.AnswerEngine;
import com.settlecal.api.answers.domain.AnswerQuery;
import com.settlecal.api.answers.domain.CalendarHistory;
import com.settlecal.api.answers.domain.Combine;
import com.settlecal.api.answers.domain.Dataset;
import com.settlecal.api.answers.domain.DatasetRelease;
import com.settlecal.api.answers.domain.DayAnswer;
import com.settlecal.api.answers.domain.EntryVersion;
import com.settlecal.api.answers.domain.Shifted;
import com.settlecal.api.calendars.domain.CalendarId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 */
@Service
public class AnswerService {

  private final AnswerEngine engine;
  private final CurrentDataset current;
  private final Clock clock;
  private final Cache<AnswerKey, Answered<?>> cache;

  AnswerService(
      AnswerEngine engine, CurrentDataset current, Clock clock, AnswerProperties properties) {
    this.engine = engine;
    this.current = current;
    this.clock = clock;
    this.cache = Caffeine.newBuilder().maximumSize(properties.cacheMaxEntries()).build();
  }

  /** The request, with as_of already capped at the release's, plus the release itself. */
  private record AnswerKey(String operation, List<Object> request, DatasetRelease release) {}

  public Answered<List<CalendarSummary>> calendars(AsOf asOf) {
    return answer(
        "calendars",
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
        List.of(calendar, from, to),
        asOf,
        (dataset, at) -> engine.closures(dataset, calendar, from, to, at),
        dataset -> List.of(calendar));
  }

  private interface Question<T> {
    T ask(Dataset dataset, Instant asOf);
  }

  // Unchecked: each key's operation always caches the same answer type.
  @SuppressWarnings("unchecked")
  private <T> Answered<T> answer(
      String operation,
      List<Object> request,
      AsOf asOf,
      Question<T> question,
      Function<Dataset, Collection<CalendarId>> calendarsUsed) {
    Dataset dataset = current.get();
    Instant at = cappedAsOf(asOf, dataset);
    AnswerKey key = new AnswerKey(operation, List.of(request, at), dataset.release());
    return (Answered<T>)
        cache.get(
            key,
            k ->
                new Answered<>(
                    question.ask(dataset, at),
                    dataset.release(),
                    at,
                    revisions(dataset, calendarsUsed.apply(dataset), at)));
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
    return new CalendarSummary(calendar.rules(), calendar.revisionAt(at), calendar.coverageAt(at));
  }
}
