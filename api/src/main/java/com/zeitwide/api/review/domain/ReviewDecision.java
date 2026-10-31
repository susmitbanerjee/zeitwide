package com.zeitwide.api.review.domain;

import java.util.Objects;

/** A reviewer's approval or rejection, with who decided and why. */
public record ReviewDecision(Verdict verdict, String reviewer, String reason) {

  // The same limits as the candidate_change columns and the pipeline's review CLI.
  public static final int MAX_REVIEWER_LENGTH = 255;
  public static final int MAX_REASON_LENGTH = 1000;

  public ReviewDecision {
    Objects.requireNonNull(verdict, "verdict");
    Objects.requireNonNull(reviewer, "reviewer");
    Objects.requireNonNull(reason, "reason");
    reviewer = reviewer.strip();
    reason = reason.strip();
    if (reviewer.isEmpty() || reviewer.length() > MAX_REVIEWER_LENGTH) {
      throw new IllegalArgumentException(
          "A reviewer is 1 to " + MAX_REVIEWER_LENGTH + " characters");
    }
    if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException("A reason is 1 to " + MAX_REASON_LENGTH + " characters");
    }
  }

  public CandidateStatus resultingStatus() {
    return switch (verdict) {
      case APPROVE -> CandidateStatus.APPROVED;
      case REJECT -> CandidateStatus.REJECTED;
    };
  }

  public enum Verdict {
    APPROVE,
    REJECT
  }
}
