package com.zeitwide.api.changes.app;

public final class InvalidChangeCursorException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public InvalidChangeCursorException() {
    super("The change cursor is invalid or belongs to different filters");
  }
}
