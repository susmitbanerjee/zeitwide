package com.zeitwide.api.auth.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvalidKeyThrottleTest {

  @Test
  void anAddressIsBlockedOnlyAfterItsFailedAttemptsAreUsedUp() {
    var throttle = new InvalidKeyThrottle();
    for (int i = 0; i < InvalidKeyThrottle.MAX_FAILURES - 1; i++) {
      throttle.recordFailure("203.0.113.7");
    }
    assertThat(throttle.isBlocked("203.0.113.7")).isFalse();

    throttle.recordFailure("203.0.113.7");
    assertThat(throttle.isBlocked("203.0.113.7")).isTrue();
  }

  @Test
  void otherAddressesAreNotAffected() {
    var throttle = new InvalidKeyThrottle();
    for (int i = 0; i < InvalidKeyThrottle.MAX_FAILURES; i++) {
      throttle.recordFailure("203.0.113.7");
    }
    assertThat(throttle.isBlocked("198.51.100.9")).isFalse();
  }
}
