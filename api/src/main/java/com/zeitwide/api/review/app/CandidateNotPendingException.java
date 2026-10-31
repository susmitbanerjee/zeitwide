package com.zeitwide.api.review.app;

public final class CandidateNotPendingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CandidateNotPendingException() {
    super("The candidate change was already reviewed");
  }
}
