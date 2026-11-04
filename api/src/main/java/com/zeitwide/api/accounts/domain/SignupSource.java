package com.zeitwide.api.accounts.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The AI assistant that referred the visit an account signed up from, as the edge Worker tags it in
 * the {@code zw_ref} cookie ({@code ai:<vendor>}). A vendor only, never anything about a person.
 */
public enum SignupSource {
  OPENAI("openai"),
  PERPLEXITY("perplexity"),
  ANTHROPIC("anthropic"),
  GOOGLE("google"),
  MICROSOFT("microsoft");

  private static final String PREFIX = "ai:";

  private final String vendor;

  SignupSource(String vendor) {
    this.vendor = vendor;
  }

  /** The vendor alone, such as {@code openai}: the metric label. */
  public String vendor() {
    return vendor;
  }

  /** The stored and transmitted form, such as {@code ai:openai}. */
  public String value() {
    return PREFIX + vendor;
  }

  /** The source an exact {@code ai:<vendor>} value names; empty for anything else. */
  public static Optional<SignupSource> fromValue(String value) {
    if (value == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(source -> source.value().equals(value)).findFirst();
  }
}
