package com.settlecal.api.auth.app;

import com.settlecal.api.auth.domain.StoredApiKey;
import java.util.Optional;

public interface ApiKeyRepository {

  /** The key with this prefix, unless it doesn't exist or was revoked. */
  Optional<StoredApiKey> findActiveByPrefix(String prefix);
}
