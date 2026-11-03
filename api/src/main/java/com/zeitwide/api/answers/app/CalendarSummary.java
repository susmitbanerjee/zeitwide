package com.zeitwide.api.answers.app;

import com.zeitwide.api.answers.domain.CalendarRules;
import com.zeitwide.api.answers.domain.Coverage;
import java.time.Instant;
import java.util.Objects;

/**
 * A calendar's rules, its revision and what it can answer for, at one instant.
 *
 * @param revisedAt when {@code revision} was published, or null at revision 0
 */
public record CalendarSummary(
    CalendarRules rules, long revision, Instant revisedAt, Coverage coverage) {

  public CalendarSummary {
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(coverage, "coverage");
  }
}
