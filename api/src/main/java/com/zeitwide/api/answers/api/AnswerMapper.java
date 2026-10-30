package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.Answered;
import com.zeitwide.api.answers.app.CalendarSummary;
import com.zeitwide.api.answers.domain.CalendarDay;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.answers.domain.Coverage;
import com.zeitwide.api.answers.domain.DayAnswer;
import com.zeitwide.api.answers.domain.EntryVersion;
import com.zeitwide.api.answers.domain.Shifted;
import com.zeitwide.api.answers.domain.SourceRef;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Domain answers to response bodies. No business logic: every value comes from the engine. */
final class AnswerMapper {

  private static final String BY_ENTRY = "entry";
  private static final String BY_WEEKEND_RULE = "weekend_rule";

  private AnswerMapper() {}

  static AnswerDtos.DayAnswer day(Answered<DayAnswer> answered, Combine combine) {
    DayAnswer answer = answered.answer();
    return new AnswerDtos.DayAnswer(
        answer.date(),
        answer.businessDay(),
        combine.code(),
        calendarDays(answer),
        answered.asOf(),
        answered.release().value(),
        revisions(answered),
        null);
  }

  static AnswerDtos.Shifted shifted(
      Answered<Shifted> answered, LocalDate start, int days, Combine combine) {
    Shifted shifted = answered.answer();
    return new AnswerDtos.Shifted(
        start,
        days,
        shifted.date(),
        combine.code(),
        calendarDays(shifted.landed()),
        shifted.skipped().stream()
            .map(day -> new AnswerDtos.SkippedDay(day.date(), calendarDays(day)))
            .toList(),
        answered.asOf(),
        answered.release().value(),
        revisions(answered),
        null);
  }

  static AnswerDtos.ClosureList closures(
      Answered<List<EntryVersion>> answered, String calendarId, LocalDate from, LocalDate to) {
    List<AnswerDtos.Closure> closures =
        answered.answer().stream()
            .map(
                entry ->
                    new AnswerDtos.Closure(
                        entry.date(),
                        entry.reason(),
                        entry.basis().code(),
                        entry.revision(),
                        entry.recordedFrom(),
                        sources(entry.sources())))
            .toList();
    long revision = answered.revisions().getFirst().revision();
    return new AnswerDtos.ClosureList(
        calendarId,
        from,
        to,
        closures,
        answered.asOf(),
        answered.release().value(),
        revision,
        revisions(answered),
        null);
  }

  static AnswerDtos.CalendarList calendars(Answered<List<CalendarSummary>> answered) {
    List<AnswerDtos.Calendar> calendars =
        answered.answer().stream().map(AnswerMapper::calendar).toList();
    return new AnswerDtos.CalendarList(
        calendars, answered.asOf(), answered.release().value(), revisions(answered), null);
  }

  /** The {@code Zeitwide-Calendar-Revision} header: {@code US-FEDWIRE=3, EU-T2=2}. */
  static String revisionHeader(Answered<?> answered) {
    return String.join(
        ", ",
        answered.revisions().stream()
            .map(r -> r.calendarId().value() + "=" + r.revision())
            .toList());
  }

  private static AnswerDtos.Calendar calendar(CalendarSummary summary) {
    var rules = summary.rules();
    return new AnswerDtos.Calendar(
        rules.id().value(),
        rules.name(),
        rules.kind().code(),
        rules.timezone().getId(),
        rules.weekend().stream()
            .sorted(Comparator.naturalOrder())
            .map(AnswerMapper::weekday)
            .toList(),
        rules.closesOnHolidays(),
        summary.revision(),
        coverage(summary.coverage()));
  }

  private static AnswerDtos.Coverage coverage(Coverage coverage) {
    return switch (coverage) {
      case Coverage.Unlimited unlimited -> new AnswerDtos.Coverage("unlimited", null, null);
      case Coverage.Years years -> new AnswerDtos.Coverage("years", years.from(), years.to());
      case Coverage.Nothing nothing -> new AnswerDtos.Coverage("none", null, null);
    };
  }

  private static List<AnswerDtos.CalendarDay> calendarDays(DayAnswer answer) {
    return answer.calendars().stream().map(AnswerMapper::calendarDay).toList();
  }

  private static AnswerDtos.CalendarDay calendarDay(CalendarDay day) {
    return switch (day) {
      case CalendarDay.ByEntry byEntry -> {
        EntryVersion entry = byEntry.entry();
        yield new AnswerDtos.CalendarDay(
            day.calendarId().value(),
            entry.open(),
            BY_ENTRY,
            entry.reason(),
            entry.basis().code(),
            entry.revision(),
            entry.recordedFrom(),
            sources(entry.sources()));
      }
      case CalendarDay.ByWeekendRule rule ->
          new AnswerDtos.CalendarDay(
              day.calendarId().value(),
              rule.open(),
              BY_WEEKEND_RULE,
              null,
              null,
              null,
              null,
              List.of());
    };
  }

  private static List<AnswerDtos.Source> sources(List<SourceRef> sources) {
    return sources.stream()
        .map(
            source ->
                new AnswerDtos.Source(
                    source.publisher(), source.url(), source.fetchedAt(), source.sha256()))
        .toList();
  }

  static Map<String, Long> revisions(Answered<?> answered) {
    Map<String, Long> revisions = new LinkedHashMap<>();
    answered.revisions().forEach(r -> revisions.put(r.calendarId().value(), r.revision()));
    return revisions;
  }

  private static String weekday(DayOfWeek day) {
    return day.name().toLowerCase(Locale.ROOT);
  }
}
