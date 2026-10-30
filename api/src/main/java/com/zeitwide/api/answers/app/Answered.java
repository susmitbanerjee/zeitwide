package com.zeitwide.api.answers.app;

import com.zeitwide.api.answers.domain.DatasetRelease;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An answer and what makes it reproducible: the release it came from, the instant it was read as
 * of, and the revision of each calendar it used.
 *
 * @param asOf the instant entries were read as of: the request's, or the release's if earlier
 */
public record Answered<T>(
    T answer, DatasetRelease release, Instant asOf, List<CalendarRevision> revisions) {

  public Answered {
    Objects.requireNonNull(answer, "answer");
    Objects.requireNonNull(release, "release");
    Objects.requireNonNull(asOf, "asOf");
    revisions = List.copyOf(revisions);
  }
}
