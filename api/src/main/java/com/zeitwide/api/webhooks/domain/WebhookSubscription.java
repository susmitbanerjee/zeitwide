package com.zeitwide.api.webhooks.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Account-owned webhook configuration without its signing secret. */
public record WebhookSubscription(
    UUID id, String url, List<String> events, Instant createdAt, Instant disabledAt) {

  public WebhookSubscription {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(url, "url");
    events = List.copyOf(events);
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
