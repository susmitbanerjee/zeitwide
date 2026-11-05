package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.AnswerService;
import com.zeitwide.api.answers.app.AsOf;
import com.zeitwide.api.answers.app.CalendarSummary;
import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The answers behind the A2A skill {@code settlement-date-answers}: the same REST bodies the MCP
 * tools return, plus one sentence that states the answer and cites its sources. Calendars are
 * combined with {@code all_open}: a settlement date needs every rail open.
 */
@Component
public final class SettlementAnswers {

  private static final int MAX_CITED_SOURCES = 6;
  private static final int MAX_LISTED_DAYS = 10;

  private final AnswerResponses responses;
  private final AnswerService answers;

  SettlementAnswers(AnswerResponses responses, AnswerService answers) {
    this.responses = responses;
    this.answers = answers;
  }

  /** A sentence for people and models, and the full answer as the REST API returns it. */
  public record Reply(String text, Object answer) {
    public Reply {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(answer, "answer");
    }
  }

  /** Every calendar ID in the current release, for reading calendar names out of a question. */
  public Set<String> calendarIds() {
    return answers.calendars(AsOf.now()).answer().stream()
        .map(CalendarSummary::rules)
        .map(rules -> rules.id().value())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public Reply check(List<String> calendars, LocalDate date, boolean signed) {
    AnswerDtos.DayAnswer answer =
        responses.check(new AnswerDtos.DayRequest(calendars, date, null, null), signed);
    String names = names(calendars);
    String verdict =
        answer.isBusinessDay()
            ? "Yes: " + date + " is a business day on " + names + "."
            : "No: "
                + date
                + " is not a business day on "
                + names
                + " ("
                + closed(answer.calendars())
                + ").";
    return new Reply(verdict + cite(answer.calendars().stream(), answer.datasetRelease()), answer);
  }

  public Reply next(List<String> calendars, LocalDate date, boolean signed) {
    AnswerDtos.Shifted answer =
        responses.next(new AnswerDtos.DayRequest(calendars, date, null, null), signed);
    String text =
        "The next business day after "
            + date
            + " on "
            + names(calendars)
            + " is "
            + answer.date()
            + "."
            + skipped(answer);
    return new Reply(text + cite(shiftedDays(answer), answer.datasetRelease()), answer);
  }

  public Reply add(List<String> calendars, LocalDate date, int days, boolean signed) {
    AnswerDtos.Shifted answer =
        responses.add(new AnswerDtos.AddRequest(calendars, date, days, null, null), signed);
    String sign = days < 0 ? "T" + days : "T+" + days;
    String text =
        sign
            + " from "
            + date
            + " on "
            + names(calendars)
            + " is "
            + answer.date()
            + "."
            + skipped(answer);
    return new Reply(text + cite(shiftedDays(answer), answer.datasetRelease()), answer);
  }

  public Reply closures(String calendar, LocalDate from, LocalDate to, boolean signed) {
    AnswerDtos.ClosureList answer =
        responses.closures(new CalendarId(calendar), from, to, null, signed);
    List<AnswerDtos.Closure> closures = answer.closures();
    String text;
    if (closures.isEmpty()) {
      text = calendar + " has no closures from " + from + " to " + to + ".";
    } else {
      text =
          calendar
              + " is closed on "
              + closures.size()
              + (closures.size() == 1 ? " day" : " days")
              + " from "
              + from
              + " to "
              + to
              + ": "
              + closures.stream()
                  .limit(MAX_LISTED_DAYS)
                  .map(closure -> closure.date() + " (" + closure.reason() + ")")
                  .collect(Collectors.joining(", "))
              + (closures.size() > MAX_LISTED_DAYS ? ", and more in the JSON part" : "")
              + ".";
    }
    List<AnswerDtos.Source> sources =
        closures.stream().flatMap(closure -> closure.sources().stream()).toList();
    return new Reply(text + sources(sources, answer.datasetRelease()), answer);
  }

  private static String names(List<String> calendars) {
    if (calendars.size() == 1) {
      return calendars.getFirst();
    }
    return String.join(", ", calendars.subList(0, calendars.size() - 1))
        + " and "
        + calendars.getLast();
  }

  private static String closed(List<AnswerDtos.CalendarDay> calendars) {
    return calendars.stream()
        .filter(day -> !day.isOpen())
        .map(day -> day.calendarId() + " is closed: " + why(day))
        .collect(Collectors.joining("; "));
  }

  private static String why(AnswerDtos.CalendarDay day) {
    return day.reason() == null ? "weekend" : day.reason();
  }

  private static String skipped(AnswerDtos.Shifted answer) {
    if (answer.skipped().isEmpty()) {
      return "";
    }
    List<String> days = new ArrayList<>();
    for (AnswerDtos.SkippedDay skipped :
        answer.skipped().subList(0, Math.min(MAX_LISTED_DAYS, answer.skipped().size()))) {
      days.add(skipped.date() + " (" + closed(skipped.calendars()) + ")");
    }
    return " Skipped " + String.join(", ", days) + ".";
  }

  private static Stream<AnswerDtos.CalendarDay> shiftedDays(AnswerDtos.Shifted answer) {
    return Stream.concat(
        answer.skipped().stream().flatMap(skipped -> skipped.calendars().stream()),
        answer.calendars().stream());
  }

  private static String cite(Stream<AnswerDtos.CalendarDay> days, String release) {
    return sources(
        days.filter(day -> day.sources() != null).flatMap(day -> day.sources().stream()).toList(),
        release);
  }

  private static String sources(List<AnswerDtos.Source> sources, String release) {
    List<String> cited =
        sources.stream()
            .map(source -> source.publisher() + " " + source.url())
            .distinct()
            .limit(MAX_CITED_SOURCES)
            .toList();
    String evidence =
        cited.isEmpty()
            ? " Decided by the calendars' weekend rules."
            : " Sources: " + String.join("; ", cited) + ".";
    return evidence + " Dataset release " + release + ".";
  }
}
