package com.zeitwide.api.auth.app;

public final class IdempotencyKeyConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public IdempotencyKeyConflictException() {
    super("This idempotency key has already been used");
  }
}
