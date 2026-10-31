package com.zeitwide.api.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.domain.ChangeType;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AlertRuleSettingsTest {

  private static final CalendarId CHAPS = new CalendarId("GB-CHAPS");
  private static final CalendarId FEDWIRE = new CalendarId("US-FEDWIRE");

  @Test
  void shouldMatchOnlyItsCalendarsTypesAndNotice() {
    var rule =
        new AlertRuleSettings(
            "Short notice", List.of(CHAPS), List.of(ChangeType.CLOSURE_ADDED), 14);

    assertThat(rule.matches(CHAPS, ChangeType.CLOSURE_ADDED, 14)).isTrue();
    assertThat(rule.matches(CHAPS, ChangeType.CLOSURE_ADDED, 15)).isFalse();
    assertThat(rule.matches(FEDWIRE, ChangeType.CLOSURE_ADDED, 1)).isFalse();
    assertThat(rule.matches(CHAPS, ChangeType.CLOSURE_REMOVED, 1)).isFalse();
  }

  @Test
  void shouldMatchAnyNoticeWithoutALimit() {
    var rule =
        new AlertRuleSettings("All", List.of(CHAPS), List.of(ChangeType.BASIS_CHANGED), null);

    assertThat(rule.matches(CHAPS, ChangeType.BASIS_CHANGED, 900)).isTrue();
  }

  @Test
  void shouldTrimTheName() {
    assertThat(
            new AlertRuleSettings("  GBP  ", List.of(CHAPS), List.of(ChangeType.CLOSURE_ADDED), 0)
                .name())
        .isEqualTo("GBP");
  }

  @Test
  void shouldRejectRulesThatCanNeverMatchOrAreTooBig() {
    List<ChangeType> added = List.of(ChangeType.CLOSURE_ADDED);
    assertThatThrownBy(() -> new AlertRuleSettings(" ", List.of(CHAPS), added, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AlertRuleSettings("x".repeat(101), List.of(CHAPS), added, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AlertRuleSettings("x", List.of(), added, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AlertRuleSettings("x", List.of(CHAPS, CHAPS), added, null))
        .isInstanceOf(IllegalArgumentException.class);
    List<CalendarId> many = IntStream.range(0, 21).mapToObj(i -> new CalendarId("C" + i)).toList();
    assertThatThrownBy(() -> new AlertRuleSettings("x", many, added, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AlertRuleSettings("x", List.of(CHAPS), Collections.emptyList(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AlertRuleSettings("x", List.of(CHAPS), added, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AlertRuleSettings("x", List.of(CHAPS), added, 3651))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
