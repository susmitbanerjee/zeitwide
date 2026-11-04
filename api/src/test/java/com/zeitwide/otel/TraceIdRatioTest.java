package com.zeitwide.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TraceIdRatioTest {

  // The same IDs and answers are in pipeline/tests/test_telemetry.py and web's sampling test.
  @Test
  void shouldDecideFromTheLowerHalfOfTheTraceId() {
    assertThat(TraceIdRatio.isSampled("ffffffffffffffff0000000000000000", 0.1)).isTrue();
    assertThat(TraceIdRatio.isSampled("0000000000000000199999999999a000", 0.1)).isFalse();
    assertThat(TraceIdRatio.isSampled("00000000000000001999999999999800", 0.1)).isTrue();
    assertThat(TraceIdRatio.isSampled("0000000000000000ffffffffffffffff", 0.1)).isFalse();
  }

  @Test
  void shouldKeepEverythingAtOneAndNothingAtZero() {
    assertThat(TraceIdRatio.isSampled("0000000000000000ffffffffffffffff", 1)).isTrue();
    assertThat(TraceIdRatio.isSampled("ffffffffffffffff0000000000000000", 0)).isFalse();
  }

  @Test
  void shouldRejectAnIdThatIsNotThirtyTwoHexCharacters() {
    assertThatThrownBy(() -> TraceIdRatio.isSampled("abc", 0.1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
