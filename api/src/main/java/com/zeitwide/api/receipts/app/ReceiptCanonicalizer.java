package com.zeitwide.api.receipts.app;

import com.zeitwide.api.receipts.domain.ReceiptPayload;

/** Converts receipt payloads to RFC 8785 canonical JSON bytes. */
public interface ReceiptCanonicalizer {

  byte[] canonicalize(ReceiptPayload payload);
}
