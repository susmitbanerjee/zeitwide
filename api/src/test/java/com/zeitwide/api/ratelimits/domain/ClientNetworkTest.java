package com.zeitwide.api.ratelimits.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClientNetworkTest {

  @ParameterizedTest
  @CsvSource({
    "203.0.113.7, 203.0.113.0/24",
    "203.0.113.250, 203.0.113.0/24",
    "2001:db8:1:2::5, 2001:db8:1::/48",
    "2001:0db8:0001:ffff:0:0:0:1, 2001:db8:1::/48",
    "::ffff:198.51.100.9, 198.51.100.0/24"
  })
  void shouldCountAnAddressUnderItsNetwork(String address, String network) {
    assertThat(ClientNetwork.of(address)).isEqualTo(network);
  }

  @Test
  void shouldShareOneNetworkAcrossItsAddresses() {
    assertThat(ClientNetwork.of("192.0.2.1")).isEqualTo(ClientNetwork.of("192.0.2.254"));
    assertThat(ClientNetwork.of("192.0.2.1")).isNotEqualTo(ClientNetwork.of("192.0.3.1"));
  }

  @Test
  void shouldNeverResolveSomethingThatIsNotAnAddress() {
    assertThat(ClientNetwork.of("example.com")).isEqualTo("other:example.com");
    assertThat(ClientNetwork.of("999.1.1.1")).isEqualTo("other:999.1.1.1");
    assertThat(ClientNetwork.of("not:an:address:zz")).startsWith("other:");
    assertThat(ClientNetwork.of(null)).isEqualTo("other:");
  }
}
