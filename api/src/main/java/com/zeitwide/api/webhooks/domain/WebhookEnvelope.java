package com.zeitwide.api.webhooks.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable event identity and data sent to webhook receivers. */
public record WebhookEnvelope(String id, String type, Instant createdAt, Map<String, Object> data) {

  public WebhookEnvelope {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(createdAt, "createdAt");
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
