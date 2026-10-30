package com.zeitwide.api.auth.app;

import com.zeitwide.api.auth.domain.ApiKeyMetadata;
import com.zeitwide.api.auth.domain.CreatedApiKeyRecord;
import com.zeitwide.api.auth.domain.StoredApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository {

  /** The key with this prefix, unless it doesn't exist or was revoked. */
  Optional<StoredApiKey> findActiveByPrefix(String prefix);

  /** Atomically updates last_used_at only while the key remains active. */
  boolean recordUse(UUID id);

  /** Serializes account-level key creation while the lifetime limit is checked and updated. */
  void lockForAccount(UUID accountId);

  long countForAccount(UUID accountId);

  Optional<CreatedApiKeyRecord> create(
      UUID accountId,
      String name,
      String prefix,
      byte[] secretHash,
      UUID createdBy,
      byte[] idempotencyHash);

  List<ApiKeyMetadata> listForAccount(UUID accountId);

  boolean revoke(UUID accountId, UUID keyId);
}
