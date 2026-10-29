package com.settlecal.api.answers.domain;

/** Payment rail, public holidays or exchange. */
public enum CalendarKind {
  RAIL("rail"),
  PUBLIC("public"),
  EXCHANGE("exchange");

  private final String code;

  CalendarKind(String code) {
    this.code = code;
  }

  /** The value stored in the database and returned by the API. */
  public String code() {
    return code;
  }

  public static CalendarKind fromCode(String code) {
    for (CalendarKind kind : values()) {
      if (kind.code.equals(code)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unknown calendar kind");
  }
}
