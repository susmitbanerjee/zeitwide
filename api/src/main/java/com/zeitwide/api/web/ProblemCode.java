package com.zeitwide.api.web;

import org.springframework.http.HttpStatus;

/** The stable machine codes in every error response (the Problem schema in openapi.yaml). */
public enum ProblemCode {
  VALIDATION_FAILED("validation_failed", HttpStatus.BAD_REQUEST, "The request is malformed"),
  INVALID_API_KEY("invalid_api_key", HttpStatus.UNAUTHORIZED, "The API key is not valid"),
  UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, "Authentication is required"),
  FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "The caller is not allowed to access this resource"),
  LENGTH_REQUIRED("length_required", HttpStatus.LENGTH_REQUIRED, "A content length is required"),
  PAYLOAD_TOO_LARGE("payload_too_large", HttpStatus.PAYLOAD_TOO_LARGE, "The request is too large"),
  API_KEY_LIMIT_REACHED(
      "api_key_limit_reached", HttpStatus.CONFLICT, "The API key limit was reached"),
  WEBHOOK_LIMIT_REACHED(
      "webhook_limit_reached", HttpStatus.CONFLICT, "The webhook subscription limit was reached"),
  ALERT_RULE_LIMIT_REACHED(
      "alert_rule_limit_reached", HttpStatus.CONFLICT, "The alert rule limit was reached"),
  CANDIDATE_NOT_PENDING(
      "candidate_not_pending", HttpStatus.CONFLICT, "The candidate was already reviewed"),
  IDEMPOTENCY_KEY_REUSED(
      "idempotency_key_reused", HttpStatus.CONFLICT, "The idempotency key was already used"),
  UNKNOWN_CALENDAR("unknown_calendar", HttpStatus.NOT_FOUND, "No calendar has this ID"),
  NOT_FOUND("not_found", HttpStatus.NOT_FOUND, "No such resource"),
  OUT_OF_RANGE(
      "out_of_range", HttpStatus.UNPROCESSABLE_ENTITY, "The date is outside source coverage"),
  RATE_LIMITED("rate_limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
  DATASET_UNAVAILABLE(
      "dataset_unavailable", HttpStatus.SERVICE_UNAVAILABLE, "No dataset release has been cut"),
  INTERNAL_ERROR("internal_error", HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");

  private final String code;
  private final HttpStatus status;
  private final String title;

  ProblemCode(String code, HttpStatus status, String title) {
    this.code = code;
    this.status = status;
    this.title = title;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }
}
