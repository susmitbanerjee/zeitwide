package com.settlecal.api.answers.api;

import com.settlecal.api.answers.app.AsOf;
import com.settlecal.api.answers.domain.Combine;
import com.settlecal.api.calendars.domain.CalendarId;
import java.time.OffsetDateTime;
import java.util.List;

/** Request values to use-case arguments. Formats were already validated by Bean Validation. */
final class Requests {

  private Requests() {}

  static List<CalendarId> calendars(List<String> ids) {
    return ids.stream().map(CalendarId::new).toList();
  }

  static Combine combine(String code) {
    return code == null ? Combine.ALL_OPEN : Combine.fromCode(code);
  }

  static AsOf asOf(OffsetDateTime asOf) {
    return asOf == null ? AsOf.now() : AsOf.at(asOf.toInstant());
  }
}
