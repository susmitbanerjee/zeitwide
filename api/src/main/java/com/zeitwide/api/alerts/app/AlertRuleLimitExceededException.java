package com.zeitwide.api.alerts.app;

public final class AlertRuleLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AlertRuleLimitExceededException() {
    super("The account alert rule limit was reached");
  }
}
