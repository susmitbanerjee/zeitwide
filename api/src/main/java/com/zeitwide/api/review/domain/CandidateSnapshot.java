package com.zeitwide.api.review.domain;

import java.time.Instant;
import java.util.Objects;

/** One source snapshot behind a candidate, and what its parser read for the date. */
public record CandidateSnapshot(
    String source,
    String publisher,
    String url,
    String role,
    Instant fetchedAt,
    String snapshotSha256,
    int httpStatus,
    String contentType,
    long byteSize,
    boolean isOpen,
    String reason,
    String parserVersion) {

  public CandidateSnapshot {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(fetchedAt, "fetchedAt");
    Objects.requireNonNull(snapshotSha256, "snapshotSha256");
    Objects.requireNonNull(reason, "reason");
  }
}
