package com.zeitwide.api.review.domain;

/** Where a candidate change is in review. The pipeline turns approved into published. */
public enum CandidateStatus {
  PENDING("pending"),
  APPROVED("approved"),
  REJECTED("rejected"),
  PUBLISHED("published");

  private final String code;

  CandidateStatus(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static CandidateStatus fromCode(String code) {
    for (CandidateStatus status : values()) {
      if (status.code.equals(code)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown candidate status");
  }
}
