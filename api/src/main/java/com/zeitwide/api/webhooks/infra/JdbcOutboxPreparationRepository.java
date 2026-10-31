package com.zeitwide.api.webhooks.infra;

import com.zeitwide.api.webhooks.app.OutboxPreparationRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxPreparationRepository implements OutboxPreparationRepository {

  private final JdbcClient jdbc;

  JdbcOutboxPreparationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Long> lockPendingOutbox(int limit) {
    return jdbc.sql(
            """
            SELECT id FROM outbox
            WHERE status = 'pending' AND next_attempt_at <= now()
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
        .param("limit", limit)
        .query(Long.class)
        .list();
  }

  @Override
  public void createMatchingDeliveries(List<Long> outboxIds) {
    jdbc.sql(
            """
            INSERT INTO webhook_delivery (subscription_id, outbox_id)
            SELECT subscription.id, event_outbox.id
            FROM outbox AS event_outbox
            JOIN change_event AS event ON event.id = event_outbox.change_event_id
            JOIN webhook_subscription AS subscription
              ON subscription.disabled_at IS NULL
             AND event.change_type::text = ANY(subscription.event_types::text[])
             AND (subscription.calendar_ids IS NULL
                  OR event.calendar_id = ANY(subscription.calendar_ids))
            -- An account with alert rules hears only what a rule matches (AlertRuleSettings).
            WHERE (NOT EXISTS (SELECT 1 FROM alert_rule AS rule
                               WHERE rule.account_id = subscription.account_id)
                   OR EXISTS (SELECT 1 FROM alert_rule AS rule
                              WHERE rule.account_id = subscription.account_id
                                AND event.calendar_id = ANY(rule.calendar_ids)
                                AND event.change_type::text = ANY(rule.event_types::text[])
                                AND (rule.max_notice_days IS NULL
                                     OR event.notice_days <= rule.max_notice_days)))
              AND event_outbox.id IN (:ids)
            ON CONFLICT (subscription_id, outbox_id) DO NOTHING
            """)
        .param("ids", outboxIds)
        .update();
  }

  @Override
  public void markOutboxDispatched(List<Long> outboxIds) {
    jdbc.sql(
            """
            UPDATE outbox
            SET status = 'dispatched', dispatched_at = now()
            WHERE id IN (:ids) AND status = 'pending'
            """)
        .param("ids", outboxIds)
        .update();
  }
}
