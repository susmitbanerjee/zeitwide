package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.AsOf;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.calendars.domain.CalendarId;
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

  static AsOf asOf(OffsetDateTime bodyAsOf, OffsetDateTime queryAsOf) {
    if (bodyAsOf != null
        && queryAsOf != null
        && !bodyAsOf.toInstant().equals(queryAsOf.toInstant())) {
      throw new com.zeitwide.api.web.RequestValidationException(
          "body as_of and query as_of must identify the same instant");
    }
    return asOf(queryAsOf == null ? bodyAsOf : queryAsOf);
  }
}
