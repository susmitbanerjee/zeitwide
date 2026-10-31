package com.zeitwide.api.alerts.app;

public final class AlertRuleNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AlertRuleNotFoundException() {
    super("No alert rule has that ID");
  }
}
