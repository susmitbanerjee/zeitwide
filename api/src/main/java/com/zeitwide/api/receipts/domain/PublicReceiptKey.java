package com.zeitwide.api.receipts.domain;

import java.time.Instant;
import java.util.Objects;

/** Public verification material and the interval during which receipts may use it. */
public record PublicReceiptKey(String keyId, String publicKey, Instant validFrom, Instant validTo) {

  public PublicReceiptKey {
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(publicKey, "publicKey");
    Objects.requireNonNull(validFrom, "validFrom");
    if (validTo != null && validTo.isBefore(validFrom)) {
      throw new IllegalArgumentException("validTo must not be before validFrom");
    }
  }
}
