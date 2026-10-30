package com.zeitwide.api.webhooks.app;

import com.zeitwide.api.auth.app.Entitlements;
import com.zeitwide.api.auth.domain.AccountPrincipal;
import com.zeitwide.api.changes.domain.ChangeType;
import com.zeitwide.api.webhooks.domain.WebhookSubscription;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookSubscriptionService {

  private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);
  private static final int MAX_EVENTS = 16;
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
  private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 16;
  private final WebhookSubscriptionRepository subscriptions;
  private final WebhookSubscriptionCommands commands;
  private final WebhookTargetGuard targetGuard;
  private final WebhookSecretCipher secretCipher;
  private final WebhookDeliveryClient deliveryClient;
  private final Entitlements entitlements;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public WebhookSubscriptionService(
      WebhookSubscriptionRepository subscriptions,
      WebhookSubscriptionCommands commands,
      WebhookTargetGuard targetGuard,
      WebhookSecretCipher secretCipher,
      WebhookDeliveryClient deliveryClient,
      Entitlements entitlements,
      Clock clock) {
    this.subscriptions = subscriptions;
    this.commands = commands;
    this.targetGuard = targetGuard;
    this.secretCipher = secretCipher;
    this.deliveryClient = deliveryClient;
    this.entitlements = entitlements;
    this.clock = clock;
  }

  public CreatedWebhook create(
      AccountPrincipal principal, String url, List<String> requestedEvents, String idempotencyKey) {
    entitlements.check(Entitlements.Feature.WEBHOOKS);
    String normalizedUrl = validateUrl(url);
    List<String> events = normalizeEvents(requestedEvents);
    byte[] idempotencyHash = idempotencyHash(idempotencyKey);
    byte[] entropy = new byte[32];
    random.nextBytes(entropy);
    String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    WebhookSubscriptionRepository.CreatedSubscription created =
        commands.create(
            principal.accountId(),
            normalizedUrl,
            secretCipher.encrypt(secret),
            events,
            idempotencyHash);
    log.atInfo()
        .addKeyValue("event", "webhook_created")
        .addKeyValue("account_id", principal.accountId())
        .addKeyValue("subscription_id", created.id())
        .log("Webhook subscription created");
    return new CreatedWebhook(created.id(), normalizedUrl, events, secret, created.createdAt());
  }

  public List<WebhookSubscription> list(AccountPrincipal principal) {
    entitlements.check(Entitlements.Feature.WEBHOOKS);
    return subscriptions.listForAccount(principal.accountId());
  }

  public WebhookSubscription update(
      AccountPrincipal principal, UUID id, String url, List<String> requestedEvents) {
    entitlements.check(Entitlements.Feature.WEBHOOKS);
    String normalizedUrl = validateUrl(url);
    List<String> events = normalizeEvents(requestedEvents);
    if (!commands.update(principal.accountId(), id, normalizedUrl, events)) {
      throw new WebhookNotFoundException();
    }
    log.atInfo()
        .addKeyValue("event", "webhook_updated")
        .addKeyValue("account_id", principal.accountId())
        .addKeyValue("subscription_id", id)
        .log("Webhook subscription updated");
    return subscriptions
        .findActiveForAccount(principal.accountId(), id)
        .orElseThrow(WebhookNotFoundException::new)
        .subscription();
  }

  public void delete(AccountPrincipal principal, UUID id) {
    entitlements.check(Entitlements.Feature.WEBHOOKS);
    if (commands.disable(principal.accountId(), id)) {
      log.atInfo()
          .addKeyValue("event", "webhook_deleted")
          .addKeyValue("account_id", principal.accountId())
          .addKeyValue("subscription_id", id)
          .log("Webhook subscription disabled");
    }
  }

  public TestResult sendTest(AccountPrincipal principal, UUID id) {
    entitlements.check(Entitlements.Feature.WEBHOOKS);
    var stored =
        subscriptions
            .findActiveForAccount(principal.accountId(), id)
            .orElseThrow(WebhookNotFoundException::new);
    try {
      URI uri = URI.create(stored.subscription().url());
      var target = targetGuard.resolveAndValidate(uri);
      String secret = secretCipher.decrypt(stored.encryptedSecret().bytes());
      String eventId = "evt_test_" + UUID.randomUUID();
      var envelope =
          new com.zeitwide.api.webhooks.domain.WebhookEnvelope(
              eventId, "test", clock.instant(), java.util.Map.of("message", "Zeitwide test event"));
      int status = deliveryClient.send(target, envelope, secret);
      boolean succeeded = status >= 200 && status < 300;
      log.atInfo()
          .addKeyValue("event", "webhook_test_sent")
          .addKeyValue("account_id", principal.accountId())
          .addKeyValue("subscription_id", id)
          .addKeyValue("success", succeeded)
          .addKeyValue("response_status", status)
          .log("Webhook test event sent");
      return new TestResult(succeeded, status);
    } catch (IllegalArgumentException | WebhookDeliveryException | WebhookSecretException failure) {
      log.atWarn()
          .addKeyValue("event", "webhook_test_failed")
          .addKeyValue("account_id", principal.accountId())
          .addKeyValue("subscription_id", id)
          .addKeyValue("failure", failure.getClass().getSimpleName())
          .log("Webhook test event failed");
      return new TestResult(false, null);
    }
  }

  private String validateUrl(String value) {
    try {
      URI uri = URI.create(value).normalize();
      targetGuard.resolveAndValidate(uri);
      return uri.toASCIIString();
    } catch (IllegalArgumentException invalid) {
      throw new InvalidWebhookRequestException();
    }
  }

  private static List<String> normalizeEvents(List<String> values) {
    if (values == null || values.isEmpty() || values.size() > MAX_EVENTS) {
      throw new InvalidWebhookRequestException();
    }
    try {
      return values.stream().distinct().map(ChangeType::fromCode).map(ChangeType::code).toList();
    } catch (IllegalArgumentException invalid) {
      throw new InvalidWebhookRequestException();
    }
  }

  private static byte[] idempotencyHash(String key) {
    if (key == null
        || key.length() < MIN_IDEMPOTENCY_KEY_LENGTH
        || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new InvalidWebhookRequestException();
    }
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("The JDK does not provide SHA-256", impossible);
    }
  }

  public record CreatedWebhook(
      UUID id, String url, List<String> events, String secret, Instant createdAt) {
    public CreatedWebhook {
      events = List.copyOf(events);
    }

    @Override
    public String toString() {
      return "CreatedWebhook[id="
          + id
          + ", url=<redacted>, events="
          + events
          + ", secret=<redacted>]";
    }
  }

  public record TestResult(boolean succeeded, Integer responseStatus) {}
}
