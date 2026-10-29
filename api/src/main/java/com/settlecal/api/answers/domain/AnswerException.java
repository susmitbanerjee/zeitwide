package com.settlecal.api.answers.domain;

/** An expected reason the engine can't answer, with a stable machine code. */
public abstract sealed class AnswerException extends RuntimeException
    permits UnknownCalendarException, OutOfRangeException {

  private static final long serialVersionUID = 1L;

  AnswerException(String message) {
    super(message);
  }

  /** The {@code code} in problem details and the conformance cases. */
  public abstract String code();
}
