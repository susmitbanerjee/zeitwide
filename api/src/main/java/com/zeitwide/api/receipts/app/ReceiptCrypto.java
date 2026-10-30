package com.zeitwide.api.receipts.app;

import com.zeitwide.api.receipts.domain.PublicReceiptKey;
import java.time.Instant;
import java.util.List;

/** Cryptographic operations backed by the configured Ed25519 key ring. */
public interface ReceiptCrypto {

  String activeKeyId();

  byte[] sign(Instant issuedAt, byte[] canonicalPayload);

  boolean verify(String keyId, Instant issuedAt, byte[] canonicalPayload, byte[] signature);

  List<PublicReceiptKey> publicKeys();
}
