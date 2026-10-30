package com.zeitwide.api.changes.domain;

import java.time.Instant;
import java.util.Objects;

/** The last event returned and the query whose continuation it belongs to. */
public record ChangeCursor(Instant publishedAt, long id, String queryHash) {

  public ChangeCursor {
    Objects.requireNonNull(publishedAt, "publishedAt");
    Objects.requireNonNull(queryHash, "queryHash");
    if (id < 0) {
      throw new IllegalArgumentException("cursor ID must not be negative");
    }
  }
}
