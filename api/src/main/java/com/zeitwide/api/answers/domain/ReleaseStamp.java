package com.zeitwide.api.answers.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A dataset release and the recorded-time instant it captures.
 *
 * @param asOf everything recorded up to this instant is in the release
 */
public record ReleaseStamp(DatasetRelease release, Instant asOf) {

  public ReleaseStamp {
    Objects.requireNonNull(release, "release");
    Objects.requireNonNull(asOf, "asOf");
  }
}
