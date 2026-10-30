package com.zeitwide.api.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyTokenTest {

  private static final String SECRET = "a".repeat(43);

  @Test
  void shouldSplitAKeyIntoPrefixAndSecret() {
    assertThat(ApiKeyToken.parse("sc_live_AbCd1234_" + SECRET))
        .contains(new ApiKeyToken("sc_live_AbCd1234", SECRET));
  }

  @Test
  void shouldRejectMalformedKeys() {
    assertThat(ApiKeyToken.parse("sc_prod_AbCd1234_" + SECRET)).isEmpty();
    assertThat(ApiKeyToken.parse("sc_live_AbCd123_" + SECRET)).isEmpty();
    assertThat(ApiKeyToken.parse("sc_live_AbCd1234_short")).isEmpty();
    assertThat(ApiKeyToken.parse("")).isEmpty();
  }

  @Test
  void shouldNeverPrintTheSecret() {
    ApiKeyToken token = ApiKeyToken.parse("sc_live_AbCd1234_" + SECRET).orElseThrow();

    assertThat(token.toString()).contains("sc_live_AbCd1234").doesNotContain(SECRET);
  }
}
