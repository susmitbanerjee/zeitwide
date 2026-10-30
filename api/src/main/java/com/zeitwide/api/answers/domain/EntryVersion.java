package com.zeitwide.api.answers.domain;

import com.zeitwide.api.calendars.domain.Basis;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One version of a day that differs from the weekend rule: a closure, or an open day.
 *
 * @param recordedFrom when Zeitwide started believing this version (inclusive)
 * @param recordedTo when a later version replaced it (exclusive), or {@link #STILL_CURRENT}
 */
public record EntryVersion(
    LocalDate date,
    boolean open,
    String reason,
    Basis basis,
    long revision,
    Instant recordedFrom,
    Instant recordedTo,
    List<SourceRef> sources) {

  /** The {@code recordedTo} of a version nothing has replaced. */
  public static final Instant STILL_CURRENT = Instant.MAX;

  private static final int MAX_REASON_LENGTH = 500;

  public EntryVersion {
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(basis, "basis");
    Objects.requireNonNull(recordedFrom, "recordedFrom");
    Objects.requireNonNull(recordedTo, "recordedTo");
    sources = List.copyOf(sources);
    if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException(
          "reason must be 1 to " + MAX_REASON_LENGTH + " characters");
    }
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be positive");
    }
    if (!recordedFrom.isBefore(recordedTo)) {
      throw new IllegalArgumentException("recordedFrom must be before recordedTo");
    }
  }

  /** Whether this was Zeitwide's view of the date at {@code moment}. */
  public boolean recordedAt(Instant moment) {
    return !moment.isBefore(recordedFrom) && moment.isBefore(recordedTo);
  }

  public boolean isCurrent() {
    return recordedTo.equals(STILL_CURRENT);
  }
}
