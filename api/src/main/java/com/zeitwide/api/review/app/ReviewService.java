package com.zeitwide.api.review.app;

import com.zeitwide.api.auth.app.Entitlements;
import com.zeitwide.api.review.domain.Candidate;
import com.zeitwide.api.review.domain.CandidateDetail;
import com.zeitwide.api.review.domain.CandidateStatus;
import com.zeitwide.api.review.domain.ReviewDecision;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin review queue. A decision only records who decided and why; the pipeline publishes
 * approved candidates, so the API never writes calendar entries.
 */
@Service
public class ReviewService {

  private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
  private final ReviewRepository candidates;
  private final Entitlements entitlements;
  private final Clock clock;

  public ReviewService(ReviewRepository candidates, Entitlements entitlements, Clock clock) {
    this.candidates = candidates;
    this.entitlements = entitlements;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Candidate> list(CandidateStatus status, int limit) {
    entitlements.check(Entitlements.Feature.ADMIN);
    return candidates.list(status, today(), limit);
  }

  @Transactional(readOnly = true)
  public CandidateDetail detail(long id) {
    entitlements.check(Entitlements.Feature.ADMIN);
    Candidate candidate = candidates.find(id).orElseThrow(CandidateNotFoundException::new);
    var recorded = candidates.decision(id);
    return new CandidateDetail(
        candidate,
        candidates.current(candidate.calendarId(), candidate.entryDate()).orElse(null),
        candidates.snapshots(id),
        recorded.map(ReviewRepository.Recorded::reviewer).orElse(null),
        recorded.map(ReviewRepository.Recorded::reason).orElse(null),
        recorded.map(ReviewRepository.Recorded::reviewedAt).orElse(null));
  }

  @Transactional
  public CandidateDetail decide(
      long id, ReviewDecision.Verdict verdict, String reviewer, String reason) {
    entitlements.check(Entitlements.Feature.ADMIN);
    ReviewDecision decision;
    try {
      decision = new ReviewDecision(verdict, reviewer, reason);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidReviewException(invalid);
    }
    if (!candidates.decide(id, decision, clock.instant())) {
      if (candidates.find(id).isEmpty()) {
        throw new CandidateNotFoundException();
      }
      throw new CandidateNotPendingException();
    }
    log.atInfo()
        .addKeyValue("event", "candidate_reviewed")
        .addKeyValue("candidate_id", id)
        .addKeyValue("status", decision.resultingStatus().code())
        .log("Candidate change reviewed");
    return detail(id);
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
  }
}
