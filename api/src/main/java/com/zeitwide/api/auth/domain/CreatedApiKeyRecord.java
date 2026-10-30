package com.zeitwide.api.auth.domain;

import java.time.Instant;
import java.util.UUID;

/** Persistence result for a newly inserted key, without the one-time plaintext value. */
public record CreatedApiKeyRecord(UUID id, Instant createdAt) {}
