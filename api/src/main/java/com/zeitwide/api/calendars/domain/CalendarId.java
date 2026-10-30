package com.zeitwide.api.calendars.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** A calendar's stable ID, such as {@code GB-CHAPS} or {@code US-FEDWIRE}. */
public record CalendarId(String value) {

  private static final int MAX_LENGTH = 64;
  private static final Pattern FORMAT = Pattern.compile("[A-Z0-9]+(-[A-Z0-9]+)*");

  public CalendarId {
    Objects.requireNonNull(value, "value");
    // The message leaves the value out: it may be untrusted input of any length.
    if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "A calendar ID is uppercase letters, digits and hyphens, at most "
              + MAX_LENGTH
              + " characters");
    }
  }
}
