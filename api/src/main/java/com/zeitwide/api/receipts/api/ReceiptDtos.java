package com.zeitwide.api.receipts.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zeitwide.api.receipts.domain.IssuedReceipt;
import com.zeitwide.api.receipts.domain.PublicReceiptKey;
import com.zeitwide.api.receipts.domain.ReceiptPayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ReceiptDtos {

  private ReceiptDtos() {}

  public record Receipt(
      @NotBlank @Pattern(regexp = "^rcpt_[0-9a-f]{32}$") String id,
      @NotNull @Valid Payload payload,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payloadHash,
      @NotBlank @Size(max = 64) String keyId,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{86}$") String signature) {

    public IssuedReceipt toDomain() {
      return new IssuedReceipt(id, payload.toDomain(), payloadHash, keyId, signature);
    }

    public static Receipt fromDomain(IssuedReceipt receipt) {
      return new Receipt(
          receipt.id(),
          Payload.fromDomain(receipt.payload()),
          receipt.payloadHash(),
          receipt.keyId(),
          receipt.signature());
    }
  }

  public record Payload(
      @NotNull Object request,
      @NotNull Object answer,
      @NotBlank @Size(max = 64) String datasetRelease,
      @NotNull @Size(max = 256)
          Map<@NotBlank @Size(max = 64) String, @NotNull Long> calendarRevisions,
      @NotNull Instant issuedAt) {

    private ReceiptPayload toDomain() {
      return new ReceiptPayload(request, answer, datasetRelease, calendarRevisions, issuedAt);
    }

    private static Payload fromDomain(ReceiptPayload payload) {
      return new Payload(
          payload.request(),
          payload.answer(),
          payload.datasetRelease(),
          payload.calendarRevisions(),
          payload.issuedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record KeySet(List<Key> keys) {}

  public record Key(String keyId, String publicKey, Instant validFrom, Instant validTo) {
    static Key fromDomain(PublicReceiptKey key) {
      return new Key(key.keyId(), key.publicKey(), key.validFrom(), key.validTo());
    }
  }

  public record Verification(boolean valid) {}

  public static KeySet keySet(List<PublicReceiptKey> keys) {
    return new KeySet(keys.stream().map(Key::fromDomain).toList());
  }
}
