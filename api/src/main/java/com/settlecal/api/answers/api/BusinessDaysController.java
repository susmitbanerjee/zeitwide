package com.settlecal.api.answers.api;

import com.settlecal.api.answers.app.AnswerService;
import com.settlecal.api.answers.app.Answered;
import com.settlecal.api.answers.domain.Combine;
import com.settlecal.api.answers.domain.DayAnswer;
import com.settlecal.api.answers.domain.Shifted;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/business-days")
class BusinessDaysController {

  private final AnswerService answers;

  BusinessDaysController(AnswerService answers) {
    this.answers = answers;
  }

  @PostMapping("/check")
  ResponseEntity<AnswerDtos.DayAnswer> check(@Valid @RequestBody AnswerDtos.DayRequest request) {
    Combine combine = Requests.combine(request.combine());
    Answered<DayAnswer> answered =
        answers.check(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            Requests.asOf(request.asOf()));
    return ResponseEntity.ok()
        .headers(SettleCalHeaders.of(answered))
        .body(AnswerMapper.day(answered, combine));
  }

  @PostMapping("/add")
  ResponseEntity<AnswerDtos.Shifted> add(@Valid @RequestBody AnswerDtos.AddRequest request) {
    Combine combine = Requests.combine(request.combine());
    Answered<Shifted> answered =
        answers.add(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            request.days(),
            Requests.asOf(request.asOf()));
    return ResponseEntity.ok()
        .headers(SettleCalHeaders.of(answered))
        .body(AnswerMapper.shifted(answered, request.date(), request.days(), combine));
  }

  @PostMapping("/next")
  ResponseEntity<AnswerDtos.Shifted> next(@Valid @RequestBody AnswerDtos.DayRequest request) {
    Combine combine = Requests.combine(request.combine());
    Answered<Shifted> answered =
        answers.next(
            Requests.calendars(request.calendars()),
            combine,
            request.date(),
            Requests.asOf(request.asOf()));
    return ResponseEntity.ok()
        .headers(SettleCalHeaders.of(answered))
        .body(AnswerMapper.shifted(answered, request.date(), 1, combine));
  }
}
