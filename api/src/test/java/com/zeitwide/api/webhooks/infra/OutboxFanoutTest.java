package com.zeitwide.api.webhooks.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.answers.GoldenDatabase;
import com.zeitwide.api.webhooks.app.OutboxFanoutService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Which subscriptions each published change reaches, including the account's alert rules. */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class OutboxFanoutTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private OutboxFanoutService fanout;

  private long chapsEntry;

  @BeforeEach
  void loadCalendars() {
    GoldenDatabase.load(jdbc);
    // Earlier tests may leave events behind; settle them so each test fans out only its own.
    jdbc.sql(
            "UPDATE outbox SET status = 'dispatched', dispatched_at = now() WHERE status = 'pending'")
        .update();
    chapsEntry =
        jdbc.sql(
                "SELECT id FROM calendar_entry WHERE calendar_id = 'GB-CHAPS' "
                    + "AND entry_date = DATE '2022-09-19' ORDER BY id DESC LIMIT 1")
            .query(Long.class)
            .single();
  }

  @Test
  void shouldDeliverEveryMatchingEventToAnAccountWithoutRules() {
    UUID subscription = subscription(account(), "{closure_added}");
    long added = publish("closure_added", 30);
    long removed = publish("closure_removed", 30);

    fanout.fanOutPending(100);

    assertThat(deliveries(subscription)).containsExactly(added);
    assertThat(deliveries(subscription)).doesNotContain(removed);
  }

  @Test
  void shouldDeliverOnlyWhatAnAlertRuleMatches() {
    UUID account = account();
    UUID subscription = subscription(account, "{closure_added,closure_removed}");
    jdbc.sql(
            """
            INSERT INTO alert_rule (account_id, name, calendar_ids, event_types, max_notice_days)
            VALUES (:account, 'Short notice', '{GB-CHAPS}', '{closure_added}', 14)
            """)
        .param("account", account)
        .update();
    long shortNotice = publish("closure_added", 9);
    long longNotice = publish("closure_added", 60);
    long otherType = publish("closure_removed", 3);

    fanout.fanOutPending(100);

    assertThat(deliveries(subscription))
        .containsExactly(shortNotice)
        .doesNotContain(longNotice, otherType);
  }

  private UUID account() {
    return jdbc.sql("INSERT INTO account (name) VALUES ('Fan-out test') RETURNING id")
        .query(UUID.class)
        .single();
  }

  private UUID subscription(UUID account, String events) {
    return jdbc.sql(
            """
            INSERT INTO webhook_subscription (account_id, url, signing_secret_ciphertext,
                                              event_types)
            VALUES (:account, 'https://hooks.example.com/zeitwide', '\\x00'::bytea,
                    CAST(:events AS change_type[]))
            RETURNING id
            """)
        .param("account", account)
        .param("events", events)
        .query(UUID.class)
        .single();
  }

  /** A change event and its outbox row, as the pipeline's publish step writes them. */
  private long publish(String type, int noticeDays) {
    long event =
        jdbc.sql(
                """
                INSERT INTO change_event (change_type, calendar_id, entry_date, entry_id,
                                          after_state, notice_days)
                VALUES (CAST(:type AS change_type), 'GB-CHAPS', DATE '2022-09-19', :entry,
                        '{"is_open": false}'::jsonb, :notice)
                RETURNING id
                """)
            .param("type", type)
            .param("entry", chapsEntry)
            .param("notice", noticeDays)
            .query(Long.class)
            .single();
    return jdbc.sql(
            """
            INSERT INTO outbox (change_event_id, event_type, payload)
            VALUES (:event, CAST(:type AS change_type), '{}'::jsonb)
            RETURNING id
            """)
        .param("event", event)
        .param("type", type)
        .query(Long.class)
        .single();
  }

  private List<Long> deliveries(UUID subscription) {
    return jdbc.sql(
            "SELECT outbox_id FROM webhook_delivery WHERE subscription_id = :s ORDER BY outbox_id")
        .param("s", subscription)
        .query(Long.class)
        .list();
  }
}
