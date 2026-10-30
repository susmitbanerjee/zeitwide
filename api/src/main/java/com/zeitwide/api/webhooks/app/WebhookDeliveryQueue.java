package com.zeitwide.api.webhooks.app;

import com.zeitwide.api.webhooks.domain.WebhookEnvelope;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryQueue {

  List<ClaimedDelivery> claimDue(int limit);

  void succeeded(long deliveryId, UUID claimToken, int responseStatus, Instant deliveredAt);

  void retryOrFail(
      long deliveryId,
      UUID claimToken,
      Instant nextAttemptAt,
      boolean failed,
      Integer responseStatus,
      String errorCode);

  record ClaimedDelivery(
      long deliveryId,
      UUID subscriptionId,
      long outboxId,
      UUID claimToken,
      int attempts,
      Instant deliveryCreatedAt,
      URI url,
      EncryptedWebhookSecret encryptedSecret,
      WebhookEnvelope envelope) {
    @Override
    public String toString() {
      return "ClaimedDelivery[deliveryId="
          + deliveryId
          + ", subscriptionId="
          + subscriptionId
          + ", outboxId="
          + outboxId
          + ", encryptedSecret=<redacted>, envelope="
          + envelope
          + "]";
    }
  }
}
