package com.zeitwide.api.auth.domain;

import java.util.Objects;
import java.util.UUID;

/** The account resolved from either a signed-in user's token or an API key. */
public record AccountPrincipal(UUID accountId, UUID appUserId, UUID apiKeyId) {

  public AccountPrincipal {
    Objects.requireNonNull(accountId, "accountId");
  }
}
