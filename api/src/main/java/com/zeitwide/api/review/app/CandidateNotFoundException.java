package com.zeitwide.api.review.app;

public final class CandidateNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CandidateNotFoundException() {
    super("No candidate change has that ID");
  }
}
