package com.zeitwide.api.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReviewDomainTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

  @Test
  void shouldCountNoticeFromTodayAndFlagUnderFourteenDaysAsUrgent() {
    assertThat(candidate(LocalDate.of(2026, 11, 13)).noticeDays(TODAY)).isEqualTo(13);
    assertThat(candidate(LocalDate.of(2026, 11, 13)).urgent(TODAY)).isTrue();
    assertThat(candidate(LocalDate.of(2026, 11, 14)).urgent(TODAY)).isFalse();
    assertThat(candidate(LocalDate.of(2026, 10, 1)).noticeDays(TODAY)).isNegative();
  }

  @Test
  void shouldTurnAVerdictIntoItsStatus() {
    assertThat(new ReviewDecision(ReviewDecision.Verdict.APPROVE, "kc-1", "ok").resultingStatus())
        .isEqualTo(CandidateStatus.APPROVED);
    assertThat(new ReviewDecision(ReviewDecision.Verdict.REJECT, "kc-1", "no").resultingStatus())
        .isEqualTo(CandidateStatus.REJECTED);
  }

  @Test
  void shouldRequireAReviewerAndAReason() {
    assertThatThrownBy(() -> new ReviewDecision(ReviewDecision.Verdict.APPROVE, " ", "ok"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReviewDecision(ReviewDecision.Verdict.APPROVE, "kc-1", "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReviewDecision(ReviewDecision.Verdict.APPROVE, "kc-1", "x".repeat(1001)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReviewDecision(ReviewDecision.Verdict.APPROVE, "k".repeat(256), "ok"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldReadStoredCodes() {
    assertThat(CandidateStatus.fromCode("published")).isEqualTo(CandidateStatus.PUBLISHED);
    assertThat(Candidate.Corroboration.fromCode("corroborated"))
        .isEqualTo(Candidate.Corroboration.CORROBORATED);
    assertThatThrownBy(() -> CandidateStatus.fromCode("draft"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Candidate.Corroboration.fromCode("maybe"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Candidate candidate(LocalDate date) {
    return new Candidate(
        1,
        new CalendarId("GB-CHAPS"),
        date,
        null,
        new EntryState(false, "Holiday", null),
        Candidate.Corroboration.SINGLE_SOURCE,
        1,
        CandidateStatus.PENDING,
        Instant.parse("2026-10-30T00:00:00Z"));
  }
}
