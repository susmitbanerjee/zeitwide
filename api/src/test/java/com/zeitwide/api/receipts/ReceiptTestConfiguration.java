package com.zeitwide.api.receipts;

import com.zeitwide.api.receipts.app.ReceiptCrypto;
import com.zeitwide.api.receipts.domain.PublicReceiptKey;
import com.zeitwide.api.receipts.infra.Ed25519ReceiptCrypto;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Ephemeral signing material exists only in test contexts, never in the shipped application. */
@TestConfiguration(proxyBeanMethods = false)
public class ReceiptTestConfiguration {

  @Bean
  @Primary
  ReceiptCrypto testReceiptCrypto() throws java.security.GeneralSecurityException {
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
    return new Ed25519ReceiptCrypto(
        "sc-test",
        privatePem,
        List.of(new PublicReceiptKey("sc-test", publicPem, Instant.EPOCH, null)));
  }

  private static String pem(String label, byte[] encoded) {
    String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
  }
}
