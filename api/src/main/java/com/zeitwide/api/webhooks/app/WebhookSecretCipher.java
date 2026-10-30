package com.zeitwide.api.webhooks.app;

public interface WebhookSecretCipher {

  byte[] encrypt(String secret);

  String decrypt(byte[] ciphertext);
}
