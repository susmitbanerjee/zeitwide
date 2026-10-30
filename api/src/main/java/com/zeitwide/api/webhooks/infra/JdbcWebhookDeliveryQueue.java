package com.zeitwide.api.webhooks.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.webhooks.app.EncryptedWebhookSecret;
import com.zeitwide.api.webhooks.app.WebhookDeliveryQueue;
import com.zeitwide.api.webhooks.domain.WebhookEnvelope;
import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWebhookDeliveryQueue implements WebhookDeliveryQueue {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcWebhookDeliveryQueue(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public List<ClaimedDelivery> claimDue(int limit) {
    jdbc.sql(
            """
            UPDATE webhook_delivery
            SET status = 'failed', locked_until = NULL, claim_token = NULL,
                last_error = 'retry_window_expired', next_attempt_at = now()
            WHERE created_at <= now() - interval '24 hours'
              AND (status = 'pending' OR (status = 'sending' AND locked_until <= now()))
            """)
        .update();
    return jdbc.sql(
            """
            WITH due AS (
              SELECT delivery.id
              FROM webhook_delivery AS delivery
              JOIN webhook_subscription AS subscription
                ON subscription.id = delivery.subscription_id
              WHERE subscription.disabled_at IS NULL
                AND delivery.created_at > now() - interval '24 hours'
                AND ((delivery.status = 'pending' AND delivery.next_attempt_at <= now())
                     OR (delivery.status = 'sending' AND delivery.locked_until <= now()))
              ORDER BY delivery.next_attempt_at, delivery.id
              LIMIT :limit
              FOR UPDATE OF delivery SKIP LOCKED
            ), claimed AS (
              UPDATE webhook_delivery AS delivery
              SET status = 'sending', attempts = delivery.attempts + 1,
                  locked_until = now() + interval '1 minute', claim_token = gen_random_uuid()
              FROM due
              WHERE delivery.id = due.id
              RETURNING delivery.id, delivery.subscription_id, delivery.outbox_id,
                        delivery.claim_token, delivery.attempts, delivery.created_at
            )
            SELECT claimed.id AS delivery_id, claimed.subscription_id, claimed.outbox_id,
                   claimed.claim_token, claimed.attempts, claimed.created_at AS delivery_created_at,
                   subscription.url, subscription.signing_secret_ciphertext,
                   event_outbox.event_type::text AS event_type,
                   event_outbox.created_at AS event_created_at,
                   event_outbox.payload::text AS payload
            FROM claimed
            JOIN webhook_subscription AS subscription ON subscription.id = claimed.subscription_id
            JOIN outbox AS event_outbox ON event_outbox.id = claimed.outbox_id
            ORDER BY claimed.id
            """)
        .param("limit", limit)
        .query(this::claimed)
        .list();
  }

  @Override
  public void succeeded(long deliveryId, UUID claimToken, int responseStatus, Instant deliveredAt) {
    jdbc.sql(
            """
            UPDATE webhook_delivery
            SET status = 'succeeded', delivered_at = :delivered, last_response_status = :status,
                last_error = NULL, locked_until = NULL, claim_token = NULL
            WHERE id = :id AND status = 'sending' AND claim_token = :claim
            """)
        .param("delivered", OffsetDateTime.ofInstant(deliveredAt, ZoneOffset.UTC))
        .param("status", responseStatus)
        .param("id", deliveryId)
        .param("claim", claimToken)
        .update();
  }

  @Override
  public void retryOrFail(
      long deliveryId,
      UUID claimToken,
      Instant nextAttemptAt,
      boolean failed,
      Integer responseStatus,
      String errorCode) {
    jdbc.sql(
            """
            UPDATE webhook_delivery
            SET status = :nextStatus, next_attempt_at = :nextAttempt,
                last_response_status = :responseStatus, last_error = :error,
                locked_until = NULL, claim_token = NULL
            WHERE id = :id AND status = 'sending' AND claim_token = :claim
            """)
        .param("nextStatus", failed ? "failed" : "pending")
        .param("nextAttempt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
        .param("responseStatus", responseStatus)
        .param("error", errorCode)
        .param("id", deliveryId)
        .param("claim", claimToken)
        .update();
  }

  private ClaimedDelivery claimed(java.sql.ResultSet row, int rowNumber) throws SQLException {
    long outboxId = row.getLong("outbox_id");
    Instant eventCreatedAt = row.getObject("event_created_at", OffsetDateTime.class).toInstant();
    String eventType = row.getString("event_type");
    Map<String, Object> payload = payload(row.getString("payload"));
    return new ClaimedDelivery(
        row.getLong("delivery_id"),
        row.getObject("subscription_id", UUID.class),
        outboxId,
        row.getObject("claim_token", UUID.class),
        row.getInt("attempts"),
        row.getObject("delivery_created_at", OffsetDateTime.class).toInstant(),
        URI.create(row.getString("url")),
        new EncryptedWebhookSecret(row.getBytes("signing_secret_ciphertext")),
        new WebhookEnvelope("evt_" + outboxId, eventType, eventCreatedAt, payload));
  }

  private Map<String, Object> payload(String serialized) {
    try {
      return json.readValue(serialized, JSON_OBJECT);
    } catch (IOException failure) {
      throw new IllegalStateException("An outbox payload is not valid JSON", failure);
    }
  }
}
