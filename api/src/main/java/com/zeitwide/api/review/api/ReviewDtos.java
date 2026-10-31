package com.zeitwide.api.review.api;

import com.zeitwide.api.review.domain.Candidate;
import com.zeitwide.api.review.domain.CandidateDetail;
import com.zeitwide.api.review.domain.CandidateSnapshot;
import com.zeitwide.api.review.domain.EntryState;
import com.zeitwide.api.review.domain.ReviewDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class ReviewDtos {

  private ReviewDtos() {}

  record Decision(
      @NotNull @Pattern(regexp = "approve|reject") String decision,
      @NotBlank @Size(max = ReviewDecision.MAX_REASON_LENGTH) String reason) {

    ReviewDecision.Verdict verdict() {
      return "approve".equals(decision)
          ? ReviewDecision.Verdict.APPROVE
          : ReviewDecision.Verdict.REJECT;
    }
  }

  record CandidateList(List<CandidateView> candidates) {}

  record State(boolean isOpen, String reason, String basis) {
    static State from(EntryState state) {
      return state == null
          ? null
          : new State(
              state.isOpen(), state.reason(), state.basis() == null ? null : state.basis().code());
    }
  }

  record CandidateView(
      long id,
      String calendarId,
      LocalDate entryDate,
      State before,
      State after,
      String corroboration,
      int sourceCount,
      int noticeDays,
      boolean urgent,
      String status,
      Instant createdAt) {

    static CandidateView from(Candidate candidate, LocalDate today) {
      return new CandidateView(
          candidate.id(),
          candidate.calendarId().value(),
          candidate.entryDate(),
          State.from(candidate.before()),
          State.from(candidate.after()),
          candidate.corroboration().code(),
          candidate.sourceCount(),
          candidate.noticeDays(today),
          candidate.urgent(today),
          candidate.status().code(),
          candidate.createdAt());
    }
  }

  record Snapshot(
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

    static Snapshot from(CandidateSnapshot snapshot) {
      return new Snapshot(
          snapshot.source(),
          snapshot.publisher(),
          snapshot.url(),
          snapshot.role(),
          snapshot.fetchedAt(),
          snapshot.snapshotSha256(),
          snapshot.httpStatus(),
          snapshot.contentType(),
          snapshot.byteSize(),
          snapshot.isOpen(),
          snapshot.reason(),
          snapshot.parserVersion());
    }
  }

  record Detail(
      CandidateView candidate,
      State current,
      List<Snapshot> snapshots,
      String reviewer,
      String reviewReason,
      Instant reviewedAt) {

    static Detail from(CandidateDetail detail, LocalDate today) {
      return new Detail(
          CandidateView.from(detail.candidate(), today),
          State.from(detail.current()),
          detail.snapshots().stream().map(Snapshot::from).toList(),
          detail.reviewer(),
          detail.reviewReason(),
          detail.reviewedAt());
    }
  }
}
