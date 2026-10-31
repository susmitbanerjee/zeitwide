package com.zeitwide.api.review.domain;

import com.zeitwide.api.calendars.domain.Basis;
import java.util.Objects;

/** A date's state on one calendar: open or closed, why, and (once published) its basis. */
public record EntryState(boolean isOpen, String reason, Basis basis) {

  public EntryState {
    Objects.requireNonNull(reason, "reason");
  }
}
