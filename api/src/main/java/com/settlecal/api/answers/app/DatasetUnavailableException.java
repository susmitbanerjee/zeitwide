package com.settlecal.api.answers.app;

/** No dataset release has been cut yet, so there is nothing to answer from. */
public final class DatasetUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  DatasetUnavailableException() {
    super("No dataset release has been cut yet");
  }
}
