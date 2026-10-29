package com.settlecal.api.answers.domain;

import static com.settlecal.api.answers.domain.AnswerEngineTest.FEDWIRE;
import static com.settlecal.api.answers.domain.AnswerEngineTest.RECORDED;
import static com.settlecal.api.answers.domain.AnswerEngineTest.closure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.settlecal.api.calendars.domain.Basis;
import com.settlecal.api.calendars.domain.CalendarId;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainValuesTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 26);
  private static final Instant LATER = Instant.parse("2026-06-01T00:00:00Z");

  @Test
  void shouldRejectOverlappingVersionsOfOneDate() {
    List<EntryVersion> overlapping =
        List.of(
            closure(DAY, RECORDED, EntryVersion.STILL_CURRENT),
            closure(DAY, LATER, EntryVersion.STILL_CURRENT));

    assertThatThrownBy(
            () -> new CalendarHistory(AnswerEngineTest.rules(FEDWIRE, Set.of(), true), overlapping))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectAVersionThatEndsBeforeItStarts() {
    assertThatThrownBy(() -> closure(DAY, LATER, RECORDED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldKnowWhetherAVersionIsCurrent() {
    assertThat(closure(DAY, RECORDED, EntryVersion.STILL_CURRENT).isCurrent()).isTrue();
    assertThat(closure(DAY, RECORDED, LATER).isCurrent()).isFalse();
  }

  @Test
  void shouldRejectABlankReasonOrNonPositiveRevision() {
    assertThatThrownBy(() -> entry(" ", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry("x".repeat(501), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry("Holiday", 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectASourceWithoutAValidSha256() {
    URI url = URI.create("https://example.com");

    assertThatThrownBy(() -> new SourceRef("test", "Publisher", url, "ABC", RECORDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceRef(" ", "Publisher", url, "0".repeat(64), RECORDED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldAskAboutOneToTenDistinctCalendars() {
    List<CalendarId> eleven = Collections.nCopies(11, FEDWIRE);

    assertThatThrownBy(() -> new AnswerQuery(List.of(), Combine.ALL_OPEN, RECORDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnswerQuery(eleven, Combine.ALL_OPEN, RECORDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnswerQuery(List.of(FEDWIRE, FEDWIRE), Combine.ALL_OPEN, RECORDED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldReadCombineCodes() {
    assertThat(Combine.fromCode("any_open")).isEqualTo(Combine.ANY_OPEN);
    assertThat(Combine.ALL_OPEN.code()).isEqualTo("all_open");
    assertThatThrownBy(() -> Combine.fromCode("most_open"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldValidateReleaseIds() {
    assertThat(new DatasetRelease("2026.10.28.1").value()).isEqualTo("2026.10.28.1");
    assertThatThrownBy(() -> new DatasetRelease("2026-10-28"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectCoverageThatEndsBeforeItStarts() {
    assertThatThrownBy(() -> new Coverage.Years(2027, 2026))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Coverage.Nothing().contains(DAY)).isFalse();
  }

  private static EntryVersion entry(String reason, long revision) {
    return new EntryVersion(
        DAY,
        false,
        reason,
        Basis.PUBLISHED,
        revision,
        RECORDED,
        EntryVersion.STILL_CURRENT,
        List.of());
  }
}
