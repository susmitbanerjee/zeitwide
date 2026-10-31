package com.zeitwide.api.review.app;

public final class InvalidReviewException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidReviewException(IllegalArgumentException cause) {
    super(cause.getMessage(), cause);
  }
}
