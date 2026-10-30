package com.zeitwide.api.webhooks.app;

public final class InvalidWebhookRequestException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public InvalidWebhookRequestException() {
    super("The webhook configuration is invalid");
  }
}
