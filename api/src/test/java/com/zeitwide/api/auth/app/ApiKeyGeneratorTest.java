package com.zeitwide.api.auth.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.auth.domain.ApiKeyToken;
import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

  @Test
  void createsLiveKeysFromThirtyTwoRandomBytesAndRedactsTheirValue() {
    ApiKeyToken token = new ApiKeyGenerator().generate();

    assertThat(token.prefix()).matches("sc_live_[A-Za-z0-9]{8}");
    assertThat(token.secret()).matches("[A-Za-z0-9]{43}");
    assertThat(token.value()).isEqualTo(token.prefix() + "_" + token.secret());
    assertThat(token.toString()).doesNotContain(token.secret());
  }
}
