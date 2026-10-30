package com.zeitwide.api.webhooks.app;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional persistence commands; URL resolution and outbound calls stay outside these. */
@Service
public class WebhookSubscriptionCommands {

  private static final int MAX_SUBSCRIPTIONS_PER_ACCOUNT = 50;
  private final WebhookSubscriptionRepository subscriptions;

  public WebhookSubscriptionCommands(WebhookSubscriptionRepository subscriptions) {
    this.subscriptions = subscriptions;
  }

  @Transactional
  public WebhookSubscriptionRepository.CreatedSubscription create(
      UUID accountId,
      String url,
      byte[] encryptedSecret,
      List<String> events,
      byte[] idempotencyHash) {
    subscriptions.lockForAccount(accountId);
    if (subscriptions.countForAccount(accountId) >= MAX_SUBSCRIPTIONS_PER_ACCOUNT) {
      throw new WebhookLimitExceededException();
    }
    return subscriptions
        .create(accountId, url, encryptedSecret, events, idempotencyHash)
        .orElseThrow(com.zeitwide.api.auth.app.IdempotencyKeyConflictException::new);
  }

  @Transactional
  public boolean update(UUID accountId, UUID id, String url, List<String> events) {
    return subscriptions.update(accountId, id, url, events);
  }

  @Transactional
  public boolean disable(UUID accountId, UUID id) {
    return subscriptions.disable(accountId, id);
  }
}
