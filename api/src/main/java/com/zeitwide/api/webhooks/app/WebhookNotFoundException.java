package com.zeitwide.api.webhooks.app;

public final class WebhookNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WebhookNotFoundException() {
    super("No active webhook subscription has that ID");
  }
}
