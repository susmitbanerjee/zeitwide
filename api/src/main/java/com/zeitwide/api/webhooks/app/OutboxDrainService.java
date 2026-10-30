package com.zeitwide.api.webhooks.app;

import com.zeitwide.api.webhooks.domain.WebhookRetryPolicy;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxDrainService {

  private static final Logger log = LoggerFactory.getLogger(OutboxDrainService.class);
  private static final int OUTBOX_BATCH_SIZE = 100;
  private static final int DELIVERY_BATCH_SIZE = 50;
  private final OutboxPreparation outbox;
  private final WebhookDeliveryQueue deliveries;
  private final WebhookSecretCipher secretCipher;
  private final WebhookTargetGuard targetGuard;
  private final WebhookDeliveryClient deliveryClient;
  private final Clock clock;

  public OutboxDrainService(
      OutboxPreparation outbox,
      WebhookDeliveryQueue deliveries,
      WebhookSecretCipher secretCipher,
      WebhookTargetGuard targetGuard,
      WebhookDeliveryClient deliveryClient,
      Clock clock) {
    this.outbox = outbox;
    this.deliveries = deliveries;
    this.secretCipher = secretCipher;
    this.targetGuard = targetGuard;
    this.deliveryClient = deliveryClient;
    this.clock = clock;
  }

  public DrainResult drain() {
    int fannedOut = outbox.fanOutPending(OUTBOX_BATCH_SIZE);
    List<WebhookDeliveryQueue.ClaimedDelivery> batch = deliveries.claimDue(DELIVERY_BATCH_SIZE);
    int succeeded = 0;
    int retried = 0;
    int failed = 0;
    for (WebhookDeliveryQueue.ClaimedDelivery delivery : batch) {
      Attempt result = deliver(delivery);
      if (result.succeeded()) {
        succeeded++;
      } else if (result.failed()) {
        failed++;
      } else {
        retried++;
      }
    }
    return new DrainResult(fannedOut, batch.size(), succeeded, retried, failed);
  }

  private Attempt deliver(WebhookDeliveryQueue.ClaimedDelivery delivery) {
    try {
      var target = targetGuard.resolveAndValidate(delivery.url());
      String secret = secretCipher.decrypt(delivery.encryptedSecret().bytes());
      int status = deliveryClient.send(target, delivery.envelope(), secret);
      if (status >= 200 && status < 300) {
        deliveries.succeeded(delivery.deliveryId(), delivery.claimToken(), status, clock.instant());
        return Attempt.success();
      }
      return retry(delivery, status, "http_status");
    } catch (UnsafeWebhookTargetException rejected) {
      return retry(delivery, null, "target_rejected");
    } catch (WebhookSecretException unavailable) {
      return retry(delivery, null, "secret_unavailable");
    } catch (WebhookDeliveryException failure) {
      return retry(delivery, null, failure.code());
    }
  }

  private Attempt retry(
      WebhookDeliveryQueue.ClaimedDelivery delivery, Integer status, String errorCode) {
    var decision =
        WebhookRetryPolicy.afterFailure(
            delivery.deliveryCreatedAt(), clock.instant(), delivery.attempts());
    deliveries.retryOrFail(
        delivery.deliveryId(),
        delivery.claimToken(),
        decision.nextAttemptAt() == null ? clock.instant() : decision.nextAttemptAt(),
        decision.failed(),
        status,
        errorCode);
    log.atWarn()
        .addKeyValue("event", "webhook_delivery_failed")
        .addKeyValue("delivery_id", delivery.deliveryId())
        .addKeyValue("subscription_id", delivery.subscriptionId())
        .addKeyValue("response_status", status)
        .addKeyValue("error_code", errorCode)
        .addKeyValue("terminal", decision.failed())
        .log("Webhook delivery attempt failed");
    return decision.failed() ? Attempt.terminal() : Attempt.retrying();
  }

  private record Attempt(boolean succeeded, boolean failed) {
    static Attempt success() {
      return new Attempt(true, false);
    }

    static Attempt retrying() {
      return new Attempt(false, false);
    }

    static Attempt terminal() {
      return new Attempt(false, true);
    }
  }

  public record DrainResult(
      int outboxEvents, int claimed, int succeeded, int retried, int failed) {}
}
