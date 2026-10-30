package com.zeitwide.api.accounts.domain;

import java.util.Objects;
import java.util.UUID;

/** Local identity linked to one stable external subject. */
public record AccountIdentity(UUID accountId, UUID appUserId) {

  public AccountIdentity {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(appUserId, "appUserId");
  }
}
