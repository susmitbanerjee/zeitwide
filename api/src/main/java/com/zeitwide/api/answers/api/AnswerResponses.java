package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.AnswerService;
import com.zeitwide.api.answers.app.Answered;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.answers.domain.DayAnswer;
import com.zeitwide.api.answers.domain.EntryVersion;
import com.zeitwide.api.answers.domain.Shifted;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.receipts.api.ReceiptDtos;
import com.zeitwide.api.receipts.app.ReceiptService;
import com.zeitwide.api.web.RequestValidationException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The REST answer bodies, built for the agent adapters (MCP tools and A2A). A signed answer carries
 * the same receipt the matching REST call would, so its request evidence names that REST route; an
 * unsigned (anonymous) one carries the dataset release and revisions but no receipt.
 */
@Component
final class AnswerResponses {

  static final int MAX_CLOSURE_RANGE_DAYS = 731;

  private final AnswerService answers;
  private final ReceiptService receipts;

  AnswerResponses(AnswerService answers, ReceiptService receipts) {
    this.answers = answers;
    this.receipts = receipts;
  }

  AnswerDtos.DayAnswer check(AnswerDtos.DayRequest request, boolean signed) {
    Combine combine = Requests.combine(request.combine());
    Answered<DayAnswer> answered =
        answers.check(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            Requests.asOf(request.asOf(), null));
    AnswerDtos.DayAnswer response = AnswerMapper.day(answered, combine);
    return signed
        ? response.withReceipt(issue(post("/v1/business-days/check", request), response, answered))
        : response;
  }

  AnswerDtos.Shifted next(AnswerDtos.DayRequest request, boolean signed) {
    Combine combine = Requests.combine(request.combine());
    Answered<Shifted> answered =
        answers.next(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            Requests.asOf(request.asOf(), null));
    AnswerDtos.Shifted response = AnswerMapper.shifted(answered, request.date(), 1, combine);
    return signed
        ? response.withReceipt(issue(post("/v1/business-days/next", request), response, answered))
        : response;
  }

  AnswerDtos.Shifted add(AnswerDtos.AddRequest request, boolean signed) {
    Combine combine = Requests.combine(request.combine());
    Answered<Shifted> answered =
        answers.add(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            request.days(),
            Requests.asOf(request.asOf(), null));
    AnswerDtos.Shifted response =
        AnswerMapper.shifted(answered, request.date(), request.days(), combine);
    return signed
        ? response.withReceipt(issue(post("/v1/business-days/add", request), response, answered))
        : response;
  }

  AnswerDtos.DayAnswer explain(
      CalendarId calendar, LocalDate date, OffsetDateTime asOf, boolean signed) {
    Answered<DayAnswer> answered =
        answers.check(List.of(calendar), Combine.ALL_OPEN, date, Requests.asOf(asOf));
    AnswerDtos.DayAnswer response = AnswerMapper.day(answered, Combine.ALL_OPEN);
    if (!signed) {
      return response;
    }
    return response.withReceipt(
        issue(
            new AnswerRequestEvidence(
                "GET",
                "/v1/calendars/" + calendar.value() + "/days/" + date,
                AnswerRequestEvidence.query(Map.of(), asOf),
                null),
            response,
            answered));
  }

  AnswerDtos.ClosureList closures(
      CalendarId calendar, LocalDate from, LocalDate to, OffsetDateTime asOf, boolean signed) {
    if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_CLOSURE_RANGE_DAYS) {
      throw new RequestValidationException("The closure range is invalid or too large");
    }
    Answered<List<EntryVersion>> answered =
        answers.closures(calendar, from, to, Requests.asOf(asOf));
    AnswerDtos.ClosureList response = AnswerMapper.closures(answered, calendar.value(), from, to);
    if (!signed) {
      return response;
    }
    return response.withReceipt(
        issue(
            new AnswerRequestEvidence(
                "GET",
                "/v1/calendars/" + calendar.value() + "/closures",
                AnswerRequestEvidence.query(Map.of("from", from, "to", to), asOf),
                null),
            response,
            answered));
  }

  private static AnswerRequestEvidence post(String path, Object body) {
    return new AnswerRequestEvidence(
        "POST", path, AnswerRequestEvidence.query(Map.of(), null), body);
  }

  private ReceiptDtos.Receipt issue(
      AnswerRequestEvidence request, Object response, Answered<?> answered) {
    return AnswerReceipts.issue(receipts, request, response, answered);
  }
}
