package com.zeitwide.api.accounts.domain;

import java.util.Map;

/**
 * How many accounts and API keys exist, for the adoption dashboard. Counts only, no identities:
 * AI-referred accounts are counted per vendor, and a vendor with none is simply absent.
 */
public record AdoptionCounts(
    long accounts,
    long activeKeys,
    long keysUsedLast30Days,
    Map<SignupSource, Long> accountsBySignupSource) {

  public AdoptionCounts {
    if (accounts < 0 || activeKeys < 0 || keysUsedLast30Days < 0) {
      throw new IllegalArgumentException("Counts are never negative");
    }
    accountsBySignupSource = Map.copyOf(accountsBySignupSource);
    if (accountsBySignupSource.values().stream().anyMatch(count -> count < 0)) {
      throw new IllegalArgumentException("Counts are never negative");
    }
  }

  /** Accounts that signed up from this AI vendor's referral. */
  public long accountsFrom(SignupSource source) {
    return accountsBySignupSource.getOrDefault(source, 0L);
  }
}
