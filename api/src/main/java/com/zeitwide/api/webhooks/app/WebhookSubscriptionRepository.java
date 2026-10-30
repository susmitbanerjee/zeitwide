package com.zeitwide.api.webhooks.app;

import com.zeitwide.api.webhooks.domain.WebhookSubscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionRepository {

  void lockForAccount(UUID accountId);

  long countForAccount(UUID accountId);

  Optional<CreatedSubscription> create(
      UUID accountId,
      String url,
      byte[] secretCiphertext,
      List<String> events,
      byte[] idempotencyKeyHash);

  List<WebhookSubscription> listForAccount(UUID accountId);

  Optional<StoredSubscription> findActiveForAccount(UUID accountId, UUID subscriptionId);

  boolean update(UUID accountId, UUID subscriptionId, String url, List<String> events);

  boolean disable(UUID accountId, UUID subscriptionId);

  record CreatedSubscription(UUID id, Instant createdAt) {}

  record StoredSubscription(
      WebhookSubscription subscription, EncryptedWebhookSecret encryptedSecret) {
    @Override
    public String toString() {
      return "StoredSubscription[subscription=" + subscription + ", encryptedSecret=<redacted>]";
    }
  }
}
