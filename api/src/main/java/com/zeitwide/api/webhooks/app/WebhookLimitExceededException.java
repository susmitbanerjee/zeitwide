package com.zeitwide.api.webhooks.app;

public final class WebhookLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WebhookLimitExceededException() {
    super("The account webhook limit was reached");
  }
}
