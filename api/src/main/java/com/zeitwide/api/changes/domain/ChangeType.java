package com.zeitwide.api.changes.domain;

import java.util.Arrays;

public enum ChangeType {
  CLOSURE_ADDED("closure_added"),
  CLOSURE_REMOVED("closure_removed"),
  CLOSURE_CHANGED("closure_changed"),
  BASIS_CHANGED("basis_changed");

  private final String code;

  ChangeType(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static ChangeType fromCode(String code) {
    return Arrays.stream(values())
        .filter(type -> type.code.equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown change type: " + code));
  }
}
