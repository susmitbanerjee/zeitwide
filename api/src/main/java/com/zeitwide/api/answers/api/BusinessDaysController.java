package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.AnswerService;
import com.zeitwide.api.answers.app.Answered;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.answers.domain.DayAnswer;
import com.zeitwide.api.answers.domain.Shifted;
import com.zeitwide.api.receipts.app.ReceiptService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/business-days")
class BusinessDaysController {

  private final AnswerService answers;
  private final ReceiptService receipts;

  BusinessDaysController(AnswerService answers, ReceiptService receipts) {
    this.answers = answers;
    this.receipts = receipts;
  }

  @PostMapping("/check")
  ResponseEntity<AnswerDtos.DayAnswer> check(
      @Valid @RequestBody AnswerDtos.DayRequest request,
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    Combine combine = Requests.combine(request.combine());
    Answered<DayAnswer> answered =
        answers.check(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            Requests.asOf(request.asOf(), asOf));
    AnswerDtos.DayAnswer body = AnswerMapper.day(answered, combine);
    body =
        body.withReceipt(
            AnswerReceipts.issue(
                receipts,
                new AnswerRequestEvidence(
                    "POST",
                    "/v1/business-days/check",
                    AnswerRequestEvidence.query(Map.of(), asOf),
                    request),
                body,
                answered));
    return ResponseEntity.ok().headers(ZeitwideHeaders.of(answered)).body(body);
  }

  @PostMapping("/add")
  ResponseEntity<AnswerDtos.Shifted> add(
      @Valid @RequestBody AnswerDtos.AddRequest request,
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    Combine combine = Requests.combine(request.combine());
    Answered<Shifted> answered =
        answers.add(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            request.days(),
            Requests.asOf(request.asOf(), asOf));
    AnswerDtos.Shifted body =
        AnswerMapper.shifted(answered, request.date(), request.days(), combine);
    body =
        body.withReceipt(
            AnswerReceipts.issue(
                receipts,
                new AnswerRequestEvidence(
                    "POST",
                    "/v1/business-days/add",
                    AnswerRequestEvidence.query(Map.of(), asOf),
                    request),
                body,
                answered));
    return ResponseEntity.ok().headers(ZeitwideHeaders.of(answered)).body(body);
  }

  @PostMapping("/next")
  ResponseEntity<AnswerDtos.Shifted> next(
      @Valid @RequestBody AnswerDtos.DayRequest request,
      @RequestParam(name = "as_of", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    Combine combine = Requests.combine(request.combine());
    Answered<Shifted> answered =
        answers.next(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            Requests.asOf(request.asOf(), asOf));
    AnswerDtos.Shifted body = AnswerMapper.shifted(answered, request.date(), 1, combine);
    body =
        body.withReceipt(
            AnswerReceipts.issue(
                receipts,
                new AnswerRequestEvidence(
                    "POST",
                    "/v1/business-days/next",
                    AnswerRequestEvidence.query(Map.of(), asOf),
                    request),
                body,
                answered));
    return ResponseEntity.ok().headers(ZeitwideHeaders.of(answered)).body(body);
  }
}
