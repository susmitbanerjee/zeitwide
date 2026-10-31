package com.zeitwide.api.operations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A cut dataset release: what it captured and the checksum of its export. */
public record ReleaseSummary(
    String id,
    Instant asOf,
    Map<String, Long> calendarRevisions,
    String exportSha256,
    Instant createdAt) {

  public ReleaseSummary {
    Objects.requireNonNull(id, "id");
    calendarRevisions = Map.copyOf(calendarRevisions);
  }
}
