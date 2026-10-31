package com.zeitwide.api.operations.domain;

import java.time.Instant;
import java.util.Objects;

/** One source's check state, as the pipeline last recorded it. */
public record SourceHealth(
    String source,
    String publisher,
    String url,
    String role,
    String schedule,
    boolean enabled,
    int consecutiveFailures,
    Instant lastCheckedAt,
    Instant lastSuccessAt,
    Instant lastSnapshotAt,
    String lastSnapshotSha256,
    String lastError,
    Instant suspendedAt) {

  public SourceHealth {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(url, "url");
  }

  /** A broken scraper must never look like "no change", so failure outranks freshness. */
  public Status status() {
    if (!enabled) {
      return Status.DISABLED;
    }
    if (suspendedAt != null) {
      return Status.SUSPENDED;
    }
    if (lastCheckedAt == null) {
      return Status.NEVER_CHECKED;
    }
    return consecutiveFailures > 0 ? Status.FAILING : Status.HEALTHY;
  }

  public enum Status {
    HEALTHY("healthy"),
    FAILING("failing"),
    SUSPENDED("suspended"),
    DISABLED("disabled"),
    NEVER_CHECKED("never_checked");

    private final String code;

    Status(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
