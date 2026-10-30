package com.zeitwide.api.auth.app;

public final class ApiKeyLimitExceededException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ApiKeyLimitExceededException() {
    super("The account has reached its API key limit");
  }
}
