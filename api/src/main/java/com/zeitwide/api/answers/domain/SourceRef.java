package com.zeitwide.api.answers.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** A source snapshot that backed an entry version: the evidence behind an answer. */
public record SourceRef(
    String sourceId, String publisher, URI url, String sha256, Instant fetchedAt) {

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public SourceRef {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(publisher, "publisher");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(sha256, "sha256");
    Objects.requireNonNull(fetchedAt, "fetchedAt");
    if (sourceId.isBlank() || publisher.isBlank()) {
      throw new IllegalArgumentException("sourceId and publisher must not be blank");
    }
    if (!SHA256.matcher(sha256).matches()) {
      throw new IllegalArgumentException("sha256 must be 64 lowercase hex digits");
    }
  }
}
