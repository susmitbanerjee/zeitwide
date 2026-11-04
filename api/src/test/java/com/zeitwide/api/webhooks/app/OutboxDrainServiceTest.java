package com.zeitwide.api.webhooks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zeitwide.api.webhooks.domain.ResolvedWebhookTarget;
import com.zeitwide.api.webhooks.domain.WebhookEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class OutboxDrainServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final MeterRegistry meters = new SimpleMeterRegistry();

  @Test
  void continuesToOtherEndpointsAfterOneDeliveryFailsAndSchedulesRetry() throws Exception {
    OutboxPreparation outbox = mock(OutboxPreparation.class);
    WebhookDeliveryQueue queue = mock(WebhookDeliveryQueue.class);
    WebhookSecretCipher cipher = mock(WebhookSecretCipher.class);
    WebhookTargetGuard guard = mock(WebhookTargetGuard.class);
    WebhookDeliveryClient client = mock(WebhookDeliveryClient.class);
    InetAddress publicIp = InetAddress.getByName("8.8.8.8");
    var target =
        new ResolvedWebhookTarget(
            URI.create("https://hooks.example.com/events"), List.of(publicIp));
    var first = delivery(1, "evt_101", NOW);
    var second = delivery(2, "evt_102", NOW);
    when(outbox.fanOutPending(100)).thenReturn(0);
    when(queue.claimDue(50)).thenReturn(List.of(first, second));
    when(cipher.decrypt(ArgumentMatchers.any(byte[].class))).thenReturn("whsec_test");
    when(guard.resolveAndValidate(ArgumentMatchers.any(URI.class))).thenReturn(target);
    when(client.send(eq(target), eq(first.envelope()), eq("whsec_test")))
        .thenThrow(new WebhookDeliveryException("network_error", new java.io.IOException()));
    when(client.send(eq(target), eq(second.envelope()), eq("whsec_test"))).thenReturn(204);

    var drain =
        new OutboxDrainService(
            outbox, queue, cipher, guard, client, Clock.fixed(NOW, ZoneOffset.UTC), meters);
    var result = drain.drain();

    assertThat(result.claimed()).isEqualTo(2);
    assertThat(result.retried()).isEqualTo(1);
    assertThat(result.succeeded()).isEqualTo(1);
    verify(queue)
        .retryOrFail(1L, first.claimToken(), NOW.plusSeconds(10), false, null, "network_error");
    verify(queue).succeeded(2L, second.claimToken(), 204, NOW);
    verify(client).send(eq(target), eq(second.envelope()), eq("whsec_test"));
    assertThat(attempts("succeeded", "succeeded")).isEqualTo(1);
    assertThat(attempts("retrying", "network_error")).isEqualTo(1);
  }

  @Test
  void marksDeliveryFailedOnceItsTwentyFourHourWindowExpires() throws Exception {
    OutboxPreparation outbox = mock(OutboxPreparation.class);
    WebhookDeliveryQueue queue = mock(WebhookDeliveryQueue.class);
    WebhookSecretCipher cipher = mock(WebhookSecretCipher.class);
    WebhookTargetGuard guard = mock(WebhookTargetGuard.class);
    WebhookDeliveryClient client = mock(WebhookDeliveryClient.class);
    var expired = delivery(3, "evt_103", NOW.minusSeconds(86_400));
    var target =
        new ResolvedWebhookTarget(
            URI.create("https://hooks.example.com/events"),
            List.of(InetAddress.getByName("8.8.8.8")));
    when(queue.claimDue(50)).thenReturn(List.of(expired));
    when(cipher.decrypt(ArgumentMatchers.any(byte[].class))).thenReturn("whsec_test");
    when(guard.resolveAndValidate(ArgumentMatchers.any(URI.class))).thenReturn(target);
    when(client.send(eq(target), eq(expired.envelope()), eq("whsec_test"))).thenReturn(500);

    var drain =
        new OutboxDrainService(
            outbox, queue, cipher, guard, client, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    assertThat(drain.drain().failed()).isEqualTo(1);
    verify(queue).retryOrFail(3L, expired.claimToken(), NOW, true, 500, "http_status");
    verify(queue, never()).succeeded(eq(3L), eq(expired.claimToken()), eq(500), eq(NOW));
    assertThat(attempts("failed", "http_status")).isEqualTo(1);
  }

  private double attempts(String outcome, String reason) {
    return meters
        .get(OutboxDrainService.DELIVERIES_METRIC)
        .tag("outcome", outcome)
        .tag("reason", reason)
        .counter()
        .count();
  }

  private static WebhookDeliveryQueue.ClaimedDelivery delivery(
      long id, String eventId, Instant createdAt) {
    return new WebhookDeliveryQueue.ClaimedDelivery(
        id,
        UUID.randomUUID(),
        id + 100,
        UUID.randomUUID(),
        1,
        createdAt,
        URI.create("https://hooks.example.com/events"),
        new EncryptedWebhookSecret(new byte[] {1, 2, 3}),
        new WebhookEnvelope(eventId, "closure_added", createdAt, Map.of("date", "2026-11-26")));
  }
}
