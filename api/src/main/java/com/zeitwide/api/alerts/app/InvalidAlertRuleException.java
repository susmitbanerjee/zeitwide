package com.zeitwide.api.alerts.app;

public final class InvalidAlertRuleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidAlertRuleException(IllegalArgumentException cause) {
    super(cause.getMessage(), cause);
  }
}
