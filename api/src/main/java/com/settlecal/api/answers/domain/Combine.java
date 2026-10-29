package com.settlecal.api.answers.domain;

import java.util.List;

/** How several calendars combine into one answer. */
public enum Combine {
  ALL_OPEN("all_open") {
    @Override
    boolean businessDay(List<CalendarDay> days) {
      return days.stream().allMatch(CalendarDay::open);
    }
  },
  ANY_OPEN("any_open") {
    @Override
    boolean businessDay(List<CalendarDay> days) {
      return days.stream().anyMatch(CalendarDay::open);
    }
  };

  private final String code;

  Combine(String code) {
    this.code = code;
  }

  abstract boolean businessDay(List<CalendarDay> days);

  /** The value in requests, responses and the conformance cases. */
  public String code() {
    return code;
  }

  public static Combine fromCode(String code) {
    for (Combine combine : values()) {
      if (combine.code.equals(code)) {
        return combine;
      }
    }
    throw new IllegalArgumentException("combine is all_open or any_open");
  }
}
