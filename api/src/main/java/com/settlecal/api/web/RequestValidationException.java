package com.settlecal.api.web;

/** A request that Bean Validation can't judge alone, such as a date range that is too long. */
public final class RequestValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RequestValidationException(String message) {
    super(message);
  }
}
