package com.settlecal.api.web;

import org.springframework.http.HttpStatus;

/** The stable machine codes in every error response (the Problem schema in openapi.yaml). */
public enum ProblemCode {
  VALIDATION_FAILED("validation_failed", HttpStatus.BAD_REQUEST, "The request is malformed"),
  INVALID_API_KEY("invalid_api_key", HttpStatus.UNAUTHORIZED, "The API key is not valid"),
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
