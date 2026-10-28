package com.settlecal.api.calendars.domain;

/** Whether an operator has published a date, or SettleCal projected it from the rules. */
public enum Basis {
  PUBLISHED("published"),
  PROJECTED("projected");

  private final String code;

  Basis(String code) {
    this.code = code;
  }

  /** The value stored in the database and returned by the API. */
  public String code() {
    return code;
  }

  public static Basis fromCode(String code) {
    for (Basis basis : values()) {
      if (basis.code.equals(code)) {
        return basis;
      }
    }
    throw new IllegalArgumentException("Unknown basis code");
  }
}
