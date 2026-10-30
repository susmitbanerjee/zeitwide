package com.zeitwide.api.receipts.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zeitwide.api.receipts.app.ReceiptService;
import com.zeitwide.api.receipts.domain.IssuedReceipt;
import com.zeitwide.api.receipts.domain.PublicReceiptKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceiptServiceTest {

  private static final Instant ROTATED_AT = Instant.parse("2026-10-29T12:00:00Z");

  @Test
  void shouldIssueJcsReceiptAndRejectTamperedPayload() throws Exception {
    KeyPair pair = keyPair();
    ReceiptService receipts =
        service("sc-2026-10", pair, List.of(publicKey("sc-2026-10", pair, null)), ROTATED_AT);

    IssuedReceipt receipt = receipts.issue(request(), answer(false), "2026.10.29.0", revisions());

    assertThat(receipts.verify(receipt)).isTrue();
    assertThat(receipt.payloadHash()).matches("[0-9a-f]{64}");
    assertThat(receipt.signature()).matches("[A-Za-z0-9_-]{86}");
    assertThat(receipt.id()).isEqualTo("rcpt_" + receipt.payloadHash().substring(0, 32));

    IssuedReceipt tampered =
        new IssuedReceipt(
            receipt.id(),
            new com.zeitwide.api.receipts.domain.ReceiptPayload(
                request(), answer(true), "2026.10.29.0", revisions(), receipt.payload().issuedAt()),
            receipt.payloadHash(),
            receipt.keyId(),
            receipt.signature());
    assertThat(receipts.verify(tampered)).isFalse();
  }

  @Test
  void shouldVerifyOldReceiptAfterSigningKeyRotates() throws Exception {
    KeyPair oldPair = keyPair();
    KeyPair newPair = keyPair();
    Instant issuedAt = ROTATED_AT.minusSeconds(1);
    PublicReceiptKey oldAtIssue = publicKey("sc-2026-09", oldPair, ROTATED_AT);
    ReceiptService oldSigner = service("sc-2026-09", oldPair, List.of(oldAtIssue), issuedAt);
    IssuedReceipt oldReceipt =
        oldSigner.issue(request(), answer(false), "2026.10.29.0", revisions());

    ReceiptService rotatedVerifier =
        service(
            "sc-2026-10",
            newPair,
            List.of(oldAtIssue, publicKey("sc-2026-10", newPair, null)),
            ROTATED_AT.plusSeconds(1));

    assertThat(rotatedVerifier.verify(oldReceipt)).isTrue();
  }

  @Test
  void shouldCanonicalizeObjectKeysAndNumbersAccordingToJcs() {
    JcsReceiptCanonicalizer canonicalizer = new JcsReceiptCanonicalizer(objectMapper());
    var payload =
        new com.zeitwide.api.receipts.domain.ReceiptPayload(
            Map.of("z", 1.0, "a", 2), Map.of(), "2026.10.29.0", revisions(), ROTATED_AT);

    String canonical =
        new String(canonicalizer.canonicalize(payload), java.nio.charset.StandardCharsets.UTF_8);

    assertThat(canonical).contains("\"request\":{\"a\":2,\"z\":1}");
    assertThat(canonical.indexOf("\"answer\""))
        .isLessThan(canonical.indexOf("\"calendar_revisions\""));
  }

  private static ReceiptService service(
      String keyId, KeyPair pair, List<PublicReceiptKey> keys, Instant now) {
    return new ReceiptService(
        new JcsReceiptCanonicalizer(objectMapper()),
        new Ed25519ReceiptCrypto(keyId, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), keys),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private static ObjectMapper objectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  private static KeyPair keyPair() throws java.security.GeneralSecurityException {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private static PublicReceiptKey publicKey(String keyId, KeyPair pair, Instant validTo) {
    return new PublicReceiptKey(
        keyId, pem("PUBLIC KEY", pair.getPublic().getEncoded()), Instant.EPOCH, validTo);
  }

  private static String pem(String label, byte[] encoded) {
    String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
  }

  private static Map<String, Object> request() {
    return Map.of(
        "path", "/v1/calendars/GB-CHAPS/days/2022-09-19", "as_of", "2022-09-09T00:00:00Z");
  }

  private static Map<String, Object> answer(boolean closed) {
    return Map.of("is_business_day", !closed, "calendar_id", "GB-CHAPS");
  }

  private static Map<String, Long> revisions() {
    return Map.of("GB-CHAPS", 1L);
  }
}
