package com.zeitwide.api.receipts.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** The canonical, self-contained evidence covered by a receipt signature. */
public record ReceiptPayload(
    Object request,
    Object answer,
    String datasetRelease,
    Map<String, Long> calendarRevisions,
    Instant issuedAt) {

  public ReceiptPayload {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(answer, "answer");
    Objects.requireNonNull(datasetRelease, "datasetRelease");
    calendarRevisions = Map.copyOf(calendarRevisions);
    Objects.requireNonNull(issuedAt, "issuedAt");
  }
}
