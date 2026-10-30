package com.zeitwide.api.webhooks.app;

public final class WebhookSecretException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WebhookSecretException(Throwable cause) {
    super("The webhook secret could not be processed", cause);
  }
}
