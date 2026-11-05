package com.zeitwide.api.a2a.domain;

import java.io.Serial;

/** A message that names no calendar or date, or asks something the skill doesn't answer. */
public final class UnreadableQuestionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public UnreadableQuestionException(String message) {
    super(message);
  }
}
