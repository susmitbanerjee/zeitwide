package com.zeitwide.api.receipts.app;

import com.zeitwide.api.receipts.domain.IssuedReceipt;
import com.zeitwide.api.receipts.domain.PublicReceiptKey;
import com.zeitwide.api.receipts.domain.ReceiptPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReceiptService {

  private static final String RECEIPT_ID_PREFIX = "rcpt_";
  private static final int ID_HASH_HEX_LENGTH = 32;

  private final ReceiptCanonicalizer canonicalizer;
  private final ReceiptCrypto crypto;
  private final Clock clock;

  public ReceiptService(ReceiptCanonicalizer canonicalizer, ReceiptCrypto crypto, Clock clock) {
    this.canonicalizer = canonicalizer;
    this.crypto = crypto;
    this.clock = clock;
  }

  public IssuedReceipt issue(
      Object request, Object answer, String release, Map<String, Long> calendarRevisions) {
    ReceiptPayload payload =
        new ReceiptPayload(request, answer, release, calendarRevisions, clock.instant());
    byte[] canonical = canonicalizer.canonicalize(payload);
    String payloadHash = HexFormat.of().formatHex(sha256(canonical));
    byte[] signature = crypto.sign(payload.issuedAt(), canonical);
    return new IssuedReceipt(
        receiptId(payloadHash),
        payload,
        payloadHash,
        crypto.activeKeyId(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
  }

  public boolean verify(IssuedReceipt receipt) {
    byte[] canonical = canonicalizer.canonicalize(receipt.payload());
    String actualHash = HexFormat.of().formatHex(sha256(canonical));
    if (!MessageDigest.isEqual(
        actualHash.getBytes(StandardCharsets.US_ASCII),
        receipt.payloadHash().getBytes(StandardCharsets.US_ASCII))) {
      return false;
    }
    if (!receiptId(actualHash).equals(receipt.id())) {
      return false;
    }
    byte[] signature;
    try {
      signature = Base64.getUrlDecoder().decode(receipt.signature());
    } catch (IllegalArgumentException invalidBase64) {
      return false;
    }
    return crypto.verify(receipt.keyId(), receipt.payload().issuedAt(), canonical, signature);
  }

  public List<PublicReceiptKey> publicKeys() {
    return crypto.publicKeys();
  }

  private static String receiptId(String payloadHash) {
    return RECEIPT_ID_PREFIX + payloadHash.substring(0, ID_HASH_HEX_LENGTH);
  }

  private static byte[] sha256(byte[] payload) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(payload);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("The JDK does not provide SHA-256", impossible);
    }
  }
}
