package com.zeitwide.api.accounts.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.PostgresJdbcTest;
import com.zeitwide.api.accounts.domain.AdoptionCounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@PostgresJdbcTest
@Import(JdbcAdoptionRepository.class)
class JdbcAdoptionRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

  @Autowired private JdbcAdoptionRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void shouldCountAccountsActiveKeysAndRecentlyUsedKeys() {
    AdoptionCounts before = repository.counts(NOW.minusSeconds(86_400));
    UUID account =
        jdbc.sql("INSERT INTO account (name) VALUES ('Adoption test') RETURNING id")
            .query(UUID.class)
            .single();
    key(account, "zw_live_Adopt001", NOW.minusSeconds(3600), null);
    key(account, "zw_live_Adopt002", NOW.minusSeconds(86_400 * 40L), null);
    key(account, "zw_live_Adopt003", NOW.minusSeconds(60), NOW.minusSeconds(30));

    AdoptionCounts after = repository.counts(NOW.minusSeconds(86_400 * 30L));

    assertThat(after.accounts()).isEqualTo(before.accounts() + 1);
    assertThat(after.activeKeys()).isEqualTo(before.activeKeys() + 2);
    assertThat(after.keysUsedLast30Days()).isEqualTo(before.keysUsedLast30Days() + 1);
  }

  private void key(UUID account, String prefix, Instant lastUsed, Instant revoked) {
    jdbc.sql(
            "INSERT INTO api_key (account_id, name, prefix, secret_sha256, last_used_at, revoked_at)"
                + " VALUES (:account, 'test', :prefix, :hash, :used, :revoked)")
        .param("account", account)
        .param("prefix", prefix)
        .param("hash", new byte[32])
        .param("used", Timestamp.from(lastUsed))
        .param("revoked", revoked == null ? null : Timestamp.from(revoked))
        .update();
  }
}
