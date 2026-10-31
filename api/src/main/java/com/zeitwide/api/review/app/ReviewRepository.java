package com.zeitwide.api.review.app;

import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.review.domain.Candidate;
import com.zeitwide.api.review.domain.CandidateSnapshot;
import com.zeitwide.api.review.domain.CandidateStatus;
import com.zeitwide.api.review.domain.EntryState;
import com.zeitwide.api.review.domain.ReviewDecision;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Candidate changes and their evidence, as the pipeline recorded them. */
public interface ReviewRepository {

  /** Urgent first (under the urgent notice as of {@code today}), then by date. */
  List<Candidate> list(CandidateStatus status, LocalDate today, int limit);

  Optional<Candidate> find(long id);

  /** The entry published now for the date, if any. */
  Optional<EntryState> current(CalendarId calendarId, LocalDate date);

  List<CandidateSnapshot> snapshots(long candidateId);

  Optional<Recorded> decision(long candidateId);

  /** Records the decision only while the candidate is pending; false if it no longer is. */
  boolean decide(long candidateId, ReviewDecision decision, Instant now);

  record Recorded(String reviewer, String reason, Instant reviewedAt) {}
}
