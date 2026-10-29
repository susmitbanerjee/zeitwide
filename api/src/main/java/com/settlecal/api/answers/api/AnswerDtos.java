package com.settlecal.api.answers.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.validator.constraints.UniqueElements;

/** The request and response bodies of openapi.yaml. JSON names are snake_case. */
final class AnswerDtos {

  static final String CALENDAR_ID = "[A-Z0-9]+(-[A-Z0-9]+)*";
  static final String COMBINE = "all_open|any_open";
  static final int MAX_CALENDARS = 10;
  static final int MAX_DAYS = 1000;
  private static final int MAX_ID_LENGTH = 64;

  private AnswerDtos() {}

  record DayRequest(
      @NotNull @Size(min = 1, max = MAX_CALENDARS) @UniqueElements
          List<@NotNull @Size(max = MAX_ID_LENGTH) @Pattern(regexp = CALENDAR_ID) String> calendars,
      @NotNull LocalDate date,
      @Pattern(regexp = COMBINE) String combine,
      OffsetDateTime asOf) {}

  record AddRequest(
      @NotNull @Size(min = 1, max = MAX_CALENDARS) @UniqueElements
          List<@NotNull @Size(max = MAX_ID_LENGTH) @Pattern(regexp = CALENDAR_ID) String> calendars,
      @NotNull LocalDate date,
      @NotNull @Min(-MAX_DAYS) @Max(MAX_DAYS) Integer days,
      @Pattern(regexp = COMBINE) String combine,
      OffsetDateTime asOf) {}

  record Source(String publisher, URI url, Instant retrievedAt, String snapshotSha256) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CalendarDay(
      String calendarId,
      boolean isOpen,
      String decidedBy,
      String reason,
      String basis,
      Long revision,
      Instant recordedFrom,
      List<Source> sources) {}

  record DayAnswer(
      LocalDate date,
      boolean isBusinessDay,
      String combine,
      List<CalendarDay> calendars,
      Instant asOf,
      String datasetRelease,
      Map<String, Long> calendarRevisions) {}

  record SkippedDay(LocalDate date, List<CalendarDay> calendars) {}

  record Shifted(
      LocalDate start,
      int days,
      LocalDate date,
      String combine,
      List<CalendarDay> calendars,
      List<SkippedDay> skipped,
      Instant asOf,
      String datasetRelease,
      Map<String, Long> calendarRevisions) {}

  record Closure(
      LocalDate date,
      String reason,
      String basis,
      long revision,
      Instant recordedFrom,
      List<Source> sources) {}

  record ClosureList(
      String calendarId,
      LocalDate from,
      LocalDate to,
      List<Closure> closures,
      Instant asOf,
      String datasetRelease,
      long calendarRevision) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Coverage(String type, LocalDate from, LocalDate to) {}

  record Calendar(
      String id,
      String name,
      String kind,
      String timezone,
      List<String> weekend,
      boolean closesOnHolidays,
      long revision,
      Coverage coverage) {}

  record CalendarList(List<Calendar> calendars, Instant asOf, String datasetRelease) {}
}
