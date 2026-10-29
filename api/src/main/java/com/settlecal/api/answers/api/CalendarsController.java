package com.settlecal.api.answers.api;

import com.settlecal.api.answers.app.AnswerService;
import com.settlecal.api.answers.app.Answered;
import com.settlecal.api.answers.domain.Combine;
import com.settlecal.api.answers.domain.DayAnswer;
import com.settlecal.api.answers.domain.EntryVersion;
import com.settlecal.api.calendars.domain.CalendarId;
import com.settlecal.api.web.RequestValidationException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/calendars")
class CalendarsController {

  /** Two years, so a page of closures stays small without pagination. */
  static final long MAX_CLOSURE_RANGE_DAYS = 731;

  private final AnswerService answers;

  CalendarsController(AnswerService answers) {
    this.answers = answers;
  }

  @GetMapping
  ResponseEntity<AnswerDtos.CalendarList> list(
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    var answered = answers.calendars(Requests.asOf(asOf));
    return ResponseEntity.ok()
        .headers(SettleCalHeaders.release(answered))
        .body(AnswerMapper.calendars(answered));
  }

  @GetMapping("/{id}/days/{date}")
  ResponseEntity<AnswerDtos.DayAnswer> day(
      @PathVariable CalendarId id,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    Answered<DayAnswer> answered =
        answers.check(List.of(id), Combine.ALL_OPEN, date, Requests.asOf(asOf));
    return ResponseEntity.ok()
        .headers(SettleCalHeaders.of(answered))
        .body(AnswerMapper.day(answered, Combine.ALL_OPEN));
  }

  @GetMapping("/{id}/closures")
  ResponseEntity<AnswerDtos.ClosureList> closures(
      @PathVariable CalendarId id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_CLOSURE_RANGE_DAYS) {
      throw new RequestValidationException(
          "to must be on or after from, at most " + MAX_CLOSURE_RANGE_DAYS + " days in all");
    }
    Answered<List<EntryVersion>> answered = answers.closures(id, from, to, Requests.asOf(asOf));
    return ResponseEntity.ok()
        .headers(SettleCalHeaders.of(answered))
        .body(AnswerMapper.closures(answered, id.value(), from, to));
  }
}
