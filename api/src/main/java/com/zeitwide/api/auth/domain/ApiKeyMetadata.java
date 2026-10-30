package com.zeitwide.api.auth.domain;

import java.time.Instant;
import java.util.UUID;

/** Safe API-key fields returned from persistence; it never contains the key hash or secret. */
public record ApiKeyMetadata(
    UUID id,
    String name,
    String prefix,
    Instant createdAt,
    Instant lastUsedAt,
    Instant revokedAt) {}
