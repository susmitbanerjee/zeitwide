package com.zeitwide.api.receipts.domain;

import java.util.Objects;

/** A receipt and the payload that it signs. */
public record IssuedReceipt(
    String id, ReceiptPayload payload, String payloadHash, String keyId, String signature) {

  public IssuedReceipt {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(payloadHash, "payloadHash");
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(signature, "signature");
  }
}
