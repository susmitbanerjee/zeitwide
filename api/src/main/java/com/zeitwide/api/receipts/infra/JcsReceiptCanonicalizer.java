package com.zeitwide.api.receipts.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.receipts.app.ReceiptCanonicalizer;
import com.zeitwide.api.receipts.domain.ReceiptPayload;
import java.nio.charset.StandardCharsets;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

/** Uses the RFC 8785 implementation for the one byte representation that is hashed and signed. */
@Component
final class JcsReceiptCanonicalizer implements ReceiptCanonicalizer {

  private final ObjectMapper json;

  JcsReceiptCanonicalizer(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public byte[] canonicalize(ReceiptPayload payload) {
    try {
      String serialized = json.writeValueAsString(payload);
      return new JsonCanonicalizer(serialized).getEncodedString().getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("The receipt payload could not be serialized", exception);
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("The receipt payload is not valid I-JSON", exception);
    }
  }
}
