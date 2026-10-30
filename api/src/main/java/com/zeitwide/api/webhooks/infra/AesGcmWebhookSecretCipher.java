package com.zeitwide.api.webhooks.infra;

import com.zeitwide.api.webhooks.app.WebhookSecretCipher;
import com.zeitwide.api.webhooks.app.WebhookSecretException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM envelope encryption for webhook signing secrets. */
final class AesGcmWebhookSecretCipher implements WebhookSecretCipher {

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final byte FORMAT_VERSION = 1;
  private static final byte[] ASSOCIATED_DATA =
      "zeitwide:webhook-secret:v1".getBytes(StandardCharsets.US_ASCII);

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  AesGcmWebhookSecretCipher(String encodedKey) {
    try {
      byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
      if (keyBytes.length != 32) {
        throw new IllegalArgumentException("The webhook encryption key must be 32 bytes");
      }
      key = new SecretKeySpec(keyBytes, "AES");
      java.util.Arrays.fill(keyBytes, (byte) 0);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "ZEITWIDE_WEBHOOK_ENCRYPTION_KEY must be a base64-encoded 32-byte key", invalid);
    }
  }

  @Override
  public byte[] encrypt(String secret) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(ASSOCIATED_DATA);
      byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.length)
          .put(FORMAT_VERSION)
          .put(nonce)
          .put(encrypted)
          .array();
    } catch (GeneralSecurityException failure) {
      throw new WebhookSecretException(failure);
    }
  }

  @Override
  public String decrypt(byte[] ciphertext) {
    if (ciphertext == null || ciphertext.length <= 1 + NONCE_BYTES + TAG_BITS / 8) {
      throw new WebhookSecretException(new IllegalArgumentException("Invalid ciphertext"));
    }
    ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
    if (buffer.get() != FORMAT_VERSION) {
      throw new WebhookSecretException(
          new IllegalArgumentException("Unsupported ciphertext version"));
    }
    byte[] nonce = new byte[NONCE_BYTES];
    buffer.get(nonce);
    byte[] encrypted = new byte[buffer.remaining()];
    buffer.get(encrypted);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(ASSOCIATED_DATA);
      byte[] plain = cipher.doFinal(encrypted);
      try {
        return new String(plain, StandardCharsets.UTF_8);
      } finally {
        java.util.Arrays.fill(plain, (byte) 0);
      }
    } catch (GeneralSecurityException failure) {
      throw new WebhookSecretException(failure);
    }
  }
}
