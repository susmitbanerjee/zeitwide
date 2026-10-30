package com.zeitwide.api.changes.app;

import com.zeitwide.api.changes.domain.ChangeEvent;
import java.time.Instant;
import java.util.List;

public record ChangeFeedPage(
    List<ChangeEvent> changes, String nextCursor, Instant since, int limit) {

  public ChangeFeedPage {
    changes = List.copyOf(changes);
  }
}
