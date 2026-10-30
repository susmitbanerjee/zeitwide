package com.zeitwide.api.webhooks.app;

public final class UnsafeWebhookTargetException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public UnsafeWebhookTargetException() {
    super("The webhook URL must resolve only to public HTTPS addresses");
  }
}
