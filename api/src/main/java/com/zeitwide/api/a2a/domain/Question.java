package com.zeitwide.api.a2a.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** What an A2A message asks, once read: one of the four answers the skill gives. */
public sealed interface Question {

  /** Is {@code date} a business day on every one of {@code calendars}? */
  record Check(List<String> calendars, LocalDate date) implements Question {
    public Check {
      calendars = List.copyOf(calendars);
      Objects.requireNonNull(date, "date");
    }
  }

  /** The first business day strictly after {@code date}. */
  record Next(List<String> calendars, LocalDate date) implements Question {
    public Next {
      calendars = List.copyOf(calendars);
      Objects.requireNonNull(date, "date");
    }
  }

  /** T+{@code days} from {@code date}; negative goes back. */
  record Add(List<String> calendars, LocalDate date, int days) implements Question {
    public Add {
      calendars = List.copyOf(calendars);
      Objects.requireNonNull(date, "date");
    }
  }

  /** Every closure of one calendar from {@code from} to {@code to}, inclusive. */
  record Closures(String calendar, LocalDate from, LocalDate to) implements Question {
    public Closures {
      Objects.requireNonNull(calendar, "calendar");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }
  }
}
