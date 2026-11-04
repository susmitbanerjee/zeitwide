package com.zeitwide.api.accounts.domain;

/** How many accounts and API keys exist, for the adoption dashboard. Counts only, no identities. */
public record AdoptionCounts(long accounts, long activeKeys, long keysUsedLast30Days) {

  public AdoptionCounts {
    if (accounts < 0 || activeKeys < 0 || keysUsedLast30Days < 0) {
      throw new IllegalArgumentException("Counts are never negative");
    }
  }
}
