package com.zeitwide.api.webhooks.infra;

import com.zeitwide.api.webhooks.app.EncryptedWebhookSecret;
import com.zeitwide.api.webhooks.app.WebhookSubscriptionRepository;
import com.zeitwide.api.webhooks.domain.WebhookSubscription;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWebhookSubscriptionRepository implements WebhookSubscriptionRepository {

  private static final int MAX_LIST_SIZE = 100;
  private final JdbcClient jdbc;

  JdbcWebhookSubscriptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockForAccount(UUID accountId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0)) IS NULL")
        .param("lock_key", "webhook:" + accountId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public long countForAccount(UUID accountId) {
    return jdbc.sql("SELECT count(*) FROM webhook_subscription WHERE account_id = :account")
        .param("account", accountId)
        .query(Long.class)
        .single();
  }

  @Override
  public Optional<CreatedSubscription> create(
      UUID accountId,
      String url,
      byte[] secretCiphertext,
      List<String> events,
      byte[] idempotencyKeyHash) {
    return jdbc.sql(
            """
            INSERT INTO webhook_subscription
                (account_id, url, signing_secret_ciphertext, event_types, idempotency_key_hash)
            VALUES (:account, :url, :secret, CAST(:events AS change_type[]), :idempotencyHash)
            ON CONFLICT (account_id, idempotency_key_hash)
                WHERE idempotency_key_hash IS NOT NULL
                DO NOTHING
            RETURNING id, created_at
            """)
        .param("account", accountId)
        .param("url", url)
        .param("secret", secretCiphertext)
        .param("events", postgresArray(events))
        .param("idempotencyHash", idempotencyKeyHash)
        .query(
            (row, rowNumber) ->
                new CreatedSubscription(
                    row.getObject("id", UUID.class),
                    row.getObject("created_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  @Override
  public List<WebhookSubscription> listForAccount(UUID accountId) {
    return jdbc.sql(
            """
            SELECT id, url, event_types::text[] AS event_types, created_at, disabled_at
            FROM webhook_subscription
            WHERE account_id = :account AND disabled_at IS NULL
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
        .param("account", accountId)
        .param("limit", MAX_LIST_SIZE)
        .query((row, rowNumber) -> subscription(row))
        .list();
  }

  @Override
  public Optional<StoredSubscription> findActiveForAccount(UUID accountId, UUID subscriptionId) {
    return jdbc.sql(
            """
            SELECT id, url, event_types::text[] AS event_types, created_at, disabled_at,
                   signing_secret_ciphertext
            FROM webhook_subscription
            WHERE id = :id AND account_id = :account AND disabled_at IS NULL
            """)
        .param("id", subscriptionId)
        .param("account", accountId)
        .query(
            (row, rowNumber) ->
                new StoredSubscription(
                    subscription(row),
                    new EncryptedWebhookSecret(row.getBytes("signing_secret_ciphertext"))))
        .optional();
  }

  @Override
  public boolean update(UUID accountId, UUID subscriptionId, String url, List<String> events) {
    return jdbc.sql(
                """
                UPDATE webhook_subscription
                SET url = :url, event_types = CAST(:events AS change_type[])
                WHERE id = :id AND account_id = :account AND disabled_at IS NULL
                """)
            .param("url", url)
            .param("events", postgresArray(events))
            .param("id", subscriptionId)
            .param("account", accountId)
            .update()
        == 1;
  }

  @Override
  public boolean disable(UUID accountId, UUID subscriptionId) {
    int updated =
        jdbc.sql(
                """
                UPDATE webhook_subscription SET disabled_at = now()
                WHERE id = :id AND account_id = :account AND disabled_at IS NULL
                """)
            .param("id", subscriptionId)
            .param("account", accountId)
            .update();
    if (updated == 1) {
      jdbc.sql(
              """
              UPDATE webhook_delivery
              SET status = 'abandoned', claim_token = NULL, locked_until = NULL,
                  last_error = 'subscription_disabled'
              WHERE subscription_id = :id AND status IN ('pending', 'sending')
              """)
          .param("id", subscriptionId)
          .update();
    }
    return updated == 1;
  }

  private static WebhookSubscription subscription(java.sql.ResultSet row) throws SQLException {
    OffsetDateTime disabled = row.getObject("disabled_at", OffsetDateTime.class);
    return new WebhookSubscription(
        row.getObject("id", UUID.class),
        row.getString("url"),
        eventTypes(row.getArray("event_types")),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        disabled == null ? null : disabled.toInstant());
  }

  private static List<String> eventTypes(Array eventArray) throws SQLException {
    Object[] values = (Object[]) eventArray.getArray();
    List<String> result = new ArrayList<>(values.length);
    for (Object value : values) {
      result.add(value.toString());
    }
    return List.copyOf(result);
  }

  private static String postgresArray(List<String> events) {
    return "{" + String.join(",", events) + "}";
  }
}
