package com.zeitwide.api.answers.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zeitwide.api.receipts.api.ReceiptDtos;
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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DayAnswer(
      LocalDate date,
      boolean isBusinessDay,
      String combine,
      List<CalendarDay> calendars,
      Instant asOf,
      String datasetRelease,
      Map<String, Long> calendarRevisions,
      ReceiptDtos.Receipt receipt) {
    DayAnswer withReceipt(ReceiptDtos.Receipt value) {
      return new DayAnswer(
          date, isBusinessDay, combine, calendars, asOf, datasetRelease, calendarRevisions, value);
    }
  }

  record SkippedDay(LocalDate date, List<CalendarDay> calendars) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RangeDay(
      LocalDate date,
      boolean isOpen,
      String decidedBy,
      String reason,
      String basis,
      List<Source> sources,
      LocalDate nextBusinessDay,
      LocalDate previousBusinessDay) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DayRange(
      String calendarId,
      LocalDate today,
      LocalDate from,
      LocalDate to,
      List<RangeDay> days,
      Instant asOf,
      String datasetRelease,
      Map<String, Long> calendarRevisions,
      ReceiptDtos.Receipt receipt) {
    DayRange withReceipt(ReceiptDtos.Receipt value) {
      return new DayRange(
          calendarId, today, from, to, days, asOf, datasetRelease, calendarRevisions, value);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Shifted(
      LocalDate start,
      int days,
      LocalDate date,
      String combine,
      List<CalendarDay> calendars,
      List<SkippedDay> skipped,
      Instant asOf,
      String datasetRelease,
      Map<String, Long> calendarRevisions,
      ReceiptDtos.Receipt receipt) {
    Shifted withReceipt(ReceiptDtos.Receipt value) {
      return new Shifted(
          start,
          days,
          date,
          combine,
          calendars,
          skipped,
          asOf,
          datasetRelease,
          calendarRevisions,
          value);
    }
  }

  record Closure(
      LocalDate date,
      String reason,
      String basis,
      long revision,
      Instant recordedFrom,
      List<Source> sources) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ClosureList(
      String calendarId,
      LocalDate from,
      LocalDate to,
      List<Closure> closures,
      Instant asOf,
      String datasetRelease,
      long calendarRevision,
      Map<String, Long> calendarRevisions,
      ReceiptDtos.Receipt receipt) {
    ClosureList withReceipt(ReceiptDtos.Receipt value) {
      return new ClosureList(
          calendarId,
          from,
          to,
          closures,
          asOf,
          datasetRelease,
          calendarRevision,
          calendarRevisions,
          value);
    }
  }

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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CalendarList(
      List<Calendar> calendars,
      Instant asOf,
      String datasetRelease,
      Map<String, Long> calendarRevisions,
      ReceiptDtos.Receipt receipt) {
    CalendarList withReceipt(ReceiptDtos.Receipt value) {
      return new CalendarList(calendars, asOf, datasetRelease, calendarRevisions, value);
    }
  }
}
