package com.zeitwide.api.changes.api;

import com.zeitwide.api.changes.app.ChangeFeedPage;
import com.zeitwide.api.changes.domain.ChangeEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class ChangeDtos {

  private ChangeDtos() {}

  static Page page(ChangeFeedPage page) {
    return new Page(
        page.changes().stream().map(ChangeDtos::change).toList(),
        page.nextCursor(),
        page.since(),
        page.limit());
  }

  private static Change change(ChangeEvent event) {
    return new Change(
        event.id(),
        event.type().code(),
        event.calendarId().value(),
        event.entryDate(),
        event.beforeState(),
        event.afterState(),
        event.noticeDays(),
        event.publishedAt());
  }

  record Page(List<Change> changes, String nextCursor, Instant since, int limit) {}

  record Change(
      long id,
      String type,
      String calendarId,
      LocalDate entryDate,
      Map<String, Object> before,
      Map<String, Object> after,
      int noticeDays,
      Instant publishedAt) {}
}
