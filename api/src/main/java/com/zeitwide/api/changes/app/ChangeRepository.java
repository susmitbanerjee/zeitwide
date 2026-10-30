package com.zeitwide.api.changes.app;

import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.domain.ChangeCursor;
import com.zeitwide.api.changes.domain.ChangeEvent;
import com.zeitwide.api.changes.domain.ChangeType;
import java.time.Instant;
import java.util.List;

public interface ChangeRepository {

  List<ChangeEvent> page(
      Instant since,
      ChangeCursor cursor,
      List<CalendarId> calendars,
      List<ChangeType> types,
      int limit);
}
