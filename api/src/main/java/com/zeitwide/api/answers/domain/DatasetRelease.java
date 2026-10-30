package com.zeitwide.api.answers.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** A dataset release ID such as {@code 2026.10.28.1}. */
public record DatasetRelease(String value) {

  private static final Pattern FORMAT = Pattern.compile("\\d{4}\\.\\d{2}\\.\\d{2}\\.\\d{1,9}");

  public DatasetRelease {
    Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("A release ID looks like 2026.10.28.1");
    }
  }
}
