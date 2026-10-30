package com.zeitwide.api.auth.app;

public final class InvalidIdempotencyKeyException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public InvalidIdempotencyKeyException() {
    super("Idempotency-Key must be between 16 and 128 characters");
  }
}
