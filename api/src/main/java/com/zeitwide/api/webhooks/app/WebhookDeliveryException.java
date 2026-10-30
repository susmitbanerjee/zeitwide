package com.zeitwide.api.webhooks.app;

public final class WebhookDeliveryException extends RuntimeException {

  private static final long serialVersionUID = 1L;
  private final String code;

  public WebhookDeliveryException(String code, Throwable cause) {
    super("The webhook request could not be completed", cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
