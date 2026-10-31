package com.zeitwide.api.review.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A candidate with what a reviewer needs to decide: the entry published now (null if none), each
 * supporting snapshot, and any decision already made.
 */
public record CandidateDetail(
    Candidate candidate,
    EntryState current,
    List<CandidateSnapshot> snapshots,
    String reviewer,
    String reviewReason,
    Instant reviewedAt) {

  public CandidateDetail {
    Objects.requireNonNull(candidate, "candidate");
    snapshots = List.copyOf(snapshots);
  }
}
