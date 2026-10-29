package com.settlecal.api.auth.domain;

import java.util.Objects;
import java.util.UUID;

/** Who is calling: an anonymous client, known only by its address, or an API key. */
public sealed interface Caller {

  record Anonymous(String clientAddress) implements Caller {
    public Anonymous {
      Objects.requireNonNull(clientAddress, "clientAddress");
    }
  }

  record Keyed(UUID apiKeyId, UUID accountId) implements Caller {
    public Keyed {
      Objects.requireNonNull(apiKeyId, "apiKeyId");
      Objects.requireNonNull(accountId, "accountId");
    }
  }
}
