package com.zeitwide.api.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SignupSourceTest {

  @ParameterizedTest
  @EnumSource(SignupSource.class)
  void shouldReadEachVendorBackFromItsValue(SignupSource source) {
    assertThat(SignupSource.fromValue("ai:" + source.vendor())).contains(source);
    assertThat(source.value()).matches("^ai:(openai|perplexity|anthropic|google|microsoft)$");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "openai",
        "ai:",
        "ai:OpenAI",
        "AI:openai",
        " ai:openai",
        "ai:openai ",
        "ai:openai;x=1",
        "ai:openai\n",
        "ai:example",
        "ai:openai,ai:google",
        "user:someone@example.com"
      })
  void shouldIgnoreAnythingButAnExactKnownValue(String forged) {
    assertThat(SignupSource.fromValue(forged)).isEmpty();
  }
}
