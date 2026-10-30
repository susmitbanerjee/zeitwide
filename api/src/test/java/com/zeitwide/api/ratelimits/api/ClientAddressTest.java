package com.zeitwide.api.ratelimits.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientAddressTest {

  private static final String REMOTE = "10.0.0.1";

  @Test
  void shouldUseTheConnectionWhenNoProxyIsTrusted() {
    assertThat(ClientAddress.of(REMOTE, "203.0.113.9", 0)).isEqualTo(REMOTE);
  }

  @Test
  void shouldTakeTheAddressOurProxyAppended() {
    assertThat(ClientAddress.of(REMOTE, "203.0.113.9, 198.51.100.7", 1)).isEqualTo("198.51.100.7");
    assertThat(ClientAddress.of(REMOTE, "203.0.113.9, 198.51.100.7, 10.1.1.1", 2))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void shouldFallBackWhenTheHeaderIsMissingOrShort() {
    assertThat(ClientAddress.of(REMOTE, null, 1)).isEqualTo(REMOTE);
    assertThat(ClientAddress.of(REMOTE, " ", 1)).isEqualTo(REMOTE);
    assertThat(ClientAddress.of(REMOTE, "198.51.100.7", 2)).isEqualTo(REMOTE);
  }
}
