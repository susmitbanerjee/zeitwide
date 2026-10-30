package com.zeitwide.api.calendars.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BasisTest {

  @ParameterizedTest
  @EnumSource(Basis.class)
  void shouldRoundTripThroughItsCode(Basis basis) {
    assertThat(Basis.fromCode(basis.code())).isEqualTo(basis);
  }

  @Test
  void shouldRejectUnknownCode() {
    assertThatThrownBy(() -> Basis.fromCode("guessed"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
