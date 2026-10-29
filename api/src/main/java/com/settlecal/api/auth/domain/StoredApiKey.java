package com.settlecal.api.auth.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An active API key as stored: never the secret itself.
 *
 * @param secretSha256Hex the SHA-256 of the key's secret part, in hex
 */
public record StoredApiKey(UUID id, UUID accountId, String secretSha256Hex) {

  public StoredApiKey {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(secretSha256Hex, "secretSha256Hex");
  }
}
