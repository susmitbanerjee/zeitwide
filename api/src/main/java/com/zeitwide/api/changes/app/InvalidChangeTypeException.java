package com.zeitwide.api.changes.app;

public final class InvalidChangeTypeException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public InvalidChangeTypeException() {
    super("One or more change types are unsupported");
  }
}
