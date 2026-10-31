package com.zeitwide.api.alerts.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A stored alert rule. It belongs to exactly one account. */
public record AlertRule(UUID id, AlertRuleSettings settings, Instant createdAt) {

  public AlertRule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
