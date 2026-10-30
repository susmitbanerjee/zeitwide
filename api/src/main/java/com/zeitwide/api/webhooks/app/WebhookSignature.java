package com.zeitwide.api.webhooks.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs the exact transmitted bytes using timestamp-dot-body HMAC-SHA256. */
public final class WebhookSignature {

  private static final String ALGORITHM = "HmacSHA256";

  private WebhookSignature() {}

  public static String header(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) '.');
      String digest = HexFormat.of().formatHex(mac.doFinal(body));
      return "t=" + timestamp + ",v1=" + digest;
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("The JDK does not provide HMAC-SHA256", impossible);
    }
  }
}
