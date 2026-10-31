package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.AnswerService;
import com.zeitwide.api.answers.app.Answered;
import com.zeitwide.api.answers.app.DayRange;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.answers.domain.DayAnswer;
import com.zeitwide.api.answers.domain.EntryVersion;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.receipts.app.ReceiptService;
import com.zeitwide.api.web.RequestValidationException;
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
  private final ReceiptService receipts;

  CalendarsController(AnswerService answers, ReceiptService receipts) {
    this.answers = answers;
    this.receipts = receipts;
  }

  @GetMapping
  ResponseEntity<AnswerDtos.CalendarList> list(
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    var answered = answers.calendars(Requests.asOf(asOf));
    AnswerDtos.CalendarList body = AnswerMapper.calendars(answered);
    body =
        body.withReceipt(
            AnswerReceipts.issue(
                receipts,
                new AnswerRequestEvidence(
                    "GET",
                    "/v1/calendars",
                    AnswerRequestEvidence.query(java.util.Map.of(), asOf),
                    null),
                body,
                answered));
    return ResponseEntity.ok().headers(ZeitwideHeaders.release(answered)).body(body);
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
    AnswerDtos.DayAnswer body = AnswerMapper.day(answered, Combine.ALL_OPEN);
    body =
        body.withReceipt(
            AnswerReceipts.issue(
                receipts,
                new AnswerRequestEvidence(
                    "GET",
                    "/v1/calendars/" + id.value() + "/days/" + date,
                    AnswerRequestEvidence.query(java.util.Map.of(), asOf),
                    null),
                body,
                answered));
    return ResponseEntity.ok().headers(ZeitwideHeaders.of(answered)).body(body);
  }

  @GetMapping("/{id}/days")
  ResponseEntity<AnswerDtos.DayRange> days(
      @PathVariable CalendarId id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    Answered<DayRange> answered = answers.days(id, from, to, Requests.asOf(asOf));
    AnswerDtos.DayRange body = AnswerMapper.days(answered, id.value());
    java.util.Map<String, Object> query = new java.util.LinkedHashMap<>();
    query.put("from", answered.answer().from());
    query.put("to", answered.answer().to());
    body =
        body.withReceipt(
            AnswerReceipts.issue(
                receipts,
                new AnswerRequestEvidence(
                    "GET",
                    "/v1/calendars/" + id.value() + "/days",
                    AnswerRequestEvidence.query(query, asOf),
                    null),
                body,
                answered));
    return ResponseEntity.ok().headers(ZeitwideHeaders.of(answered)).body(body);
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
    AnswerDtos.ClosureList body = AnswerMapper.closures(answered, id.value(), from, to);
    body =
        body.withReceipt(
            AnswerReceipts.issue(
                receipts,
                new AnswerRequestEvidence(
                    "GET",
                    "/v1/calendars/" + id.value() + "/closures",
                    AnswerRequestEvidence.query(java.util.Map.of("from", from, "to", to), asOf),
                    null),
                body,
                answered));
    return ResponseEntity.ok().headers(ZeitwideHeaders.of(answered)).body(body);
  }
}
