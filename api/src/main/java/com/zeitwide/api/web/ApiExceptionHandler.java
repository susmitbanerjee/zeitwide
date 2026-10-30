package com.zeitwide.api.web;

import com.zeitwide.api.answers.app.DatasetUnavailableException;
import com.zeitwide.api.answers.domain.OutOfRangeException;
import com.zeitwide.api.answers.domain.UnknownCalendarException;
import com.zeitwide.api.auth.app.ApiKeyLimitExceededException;
import com.zeitwide.api.auth.app.IdempotencyKeyConflictException;
import com.zeitwide.api.auth.app.InvalidIdempotencyKeyException;
import com.zeitwide.api.changes.app.InvalidChangeCursorException;
import com.zeitwide.api.changes.app.InvalidChangeTypeException;
import com.zeitwide.api.webhooks.app.InvalidWebhookRequestException;
import com.zeitwide.api.webhooks.app.WebhookLimitExceededException;
import com.zeitwide.api.webhooks.app.WebhookNotFoundException;
import jakarta.validation.ConstraintViolationException;
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

  @ExceptionHandler(ApiKeyLimitExceededException.class)
  ProblemDetail apiKeyLimitReached(ApiKeyLimitExceededException e) {
    return Problems.of(ProblemCode.API_KEY_LIMIT_REACHED, e.getMessage());
  }

  @ExceptionHandler(IdempotencyKeyConflictException.class)
  ProblemDetail idempotencyConflict(IdempotencyKeyConflictException e) {
    return Problems.of(ProblemCode.IDEMPOTENCY_KEY_REUSED, e.getMessage());
  }

  @ExceptionHandler(InvalidIdempotencyKeyException.class)
  ProblemDetail invalidIdempotencyKey(InvalidIdempotencyKeyException e) {
    return Problems.of(ProblemCode.VALIDATION_FAILED, e.getMessage());
  }

  @ExceptionHandler(InvalidWebhookRequestException.class)
  ProblemDetail invalidWebhook(InvalidWebhookRequestException e) {
    return Problems.of(ProblemCode.VALIDATION_FAILED, e.getMessage());
  }

  @ExceptionHandler(WebhookNotFoundException.class)
  ProblemDetail webhookNotFound(WebhookNotFoundException e) {
    return Problems.of(ProblemCode.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(WebhookLimitExceededException.class)
  ProblemDetail webhookLimitReached(WebhookLimitExceededException e) {
    return Problems.of(ProblemCode.WEBHOOK_LIMIT_REACHED, e.getMessage());
  }

  @ExceptionHandler({InvalidChangeCursorException.class, InvalidChangeTypeException.class})
  ProblemDetail invalidChangeQuery(IllegalArgumentException e) {
    return Problems.of(ProblemCode.VALIDATION_FAILED, e.getMessage());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail invalidQuery(ConstraintViolationException e) {
    return Problems.of(ProblemCode.VALIDATION_FAILED, "One or more query parameters are invalid");
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
