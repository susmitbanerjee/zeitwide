package com.zeitwide.api.webhooks.app;

/** Immutable ciphertext wrapper which never exposes bytes through generated record methods. */
public final class EncryptedWebhookSecret {

  private final byte[] ciphertext;

  public EncryptedWebhookSecret(byte[] ciphertext) {
    this.ciphertext = ciphertext.clone();
  }

  public byte[] bytes() {
    return ciphertext.clone();
  }

  @Override
  public String toString() {
    return "EncryptedWebhookSecret[<redacted>]";
  }
}
