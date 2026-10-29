package com.settlecal.api.web;

import com.settlecal.api.answers.app.DatasetUnavailableException;
import com.settlecal.api.answers.domain.OutOfRangeException;
import com.settlecal.api.answers.domain.UnknownCalendarException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** The one place errors become RFC 9457 problem details, each with a stable {@code code}. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final int NOT_FOUND = 404;

  @ExceptionHandler(UnknownCalendarException.class)
  ProblemDetail unknownCalendar(UnknownCalendarException e) {
    return Problems.of(
        ProblemCode.UNKNOWN_CALENDAR, "No calendar " + e.calendarId().value() + " exists");
  }

  @ExceptionHandler(OutOfRangeException.class)
  ProblemDetail outOfRange(OutOfRangeException e) {
    ProblemDetail problem = Problems.of(ProblemCode.OUT_OF_RANGE, e.getMessage());
    problem.setProperty("calendar_id", e.calendarId().value());
    problem.setProperty("date", e.date().toString());
    return problem;
  }

  @ExceptionHandler(RequestValidationException.class)
  ProblemDetail invalid(RequestValidationException e) {
    return Problems.of(ProblemCode.VALIDATION_FAILED, e.getMessage());
  }

  @ExceptionHandler(DatasetUnavailableException.class)
  ProblemDetail unavailable(DatasetUnavailableException e) {
    return Problems.of(ProblemCode.DATASET_UNAVAILABLE, e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    // The cause is logged for us; the caller gets nothing that could leak internals.
    log.atError().setCause(e).log("Unhandled error");
    return Problems.of(ProblemCode.INTERNAL_ERROR, "The error has been logged");
  }

  /** Spring's own errors (bad JSON, failed validation, unknown route) get a code too. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      ProblemCode code =
          statusCode.value() == NOT_FOUND ? ProblemCode.NOT_FOUND : ProblemCode.VALIDATION_FAILED;
      problem.setProperty(Problems.CODE, code.code());
    }
    return response;
  }
}
