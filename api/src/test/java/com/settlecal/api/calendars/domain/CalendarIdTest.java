package com.settlecal.api.calendars.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarIdTest {

  @Test
  void shouldAcceptUppercaseIdWithHyphens() {
    assertThat(new CalendarId("GB-CHAPS").value()).isEqualTo("GB-CHAPS");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "gb-chaps", "GB--CHAPS", "-GB", "GB-", "GB CHAPS", "GB_CHAPS"})
  void shouldRejectMalformedId(String value) {
    assertThatThrownBy(() -> new CalendarId(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectIdLongerThan64Characters() {
    assertThatThrownBy(() -> new CalendarId("A".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
