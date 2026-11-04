package com.zeitwide.api.accounts.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.PostgresJdbcTest;
import com.zeitwide.api.accounts.domain.AdoptionCounts;
import com.zeitwide.api.accounts.domain.SignupSource;
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

  @Test
  void shouldCountAccountsPerAiVendorAndLeaveOutTheRest() {
    AdoptionCounts before = repository.counts(NOW);
    jdbc.sql(
            "INSERT INTO account (name, signup_source) VALUES "
                + "('Referred 1', 'ai:perplexity'), ('Referred 2', 'ai:perplexity'), "
                + "('Referred 3', 'ai:microsoft'), ('Not referred', NULL)")
        .update();

    AdoptionCounts after = repository.counts(NOW);

    assertThat(after.accountsFrom(SignupSource.PERPLEXITY))
        .isEqualTo(before.accountsFrom(SignupSource.PERPLEXITY) + 2);
    assertThat(after.accountsFrom(SignupSource.MICROSOFT))
        .isEqualTo(before.accountsFrom(SignupSource.MICROSOFT) + 1);
    assertThat(after.accountsFrom(SignupSource.OPENAI))
        .isEqualTo(before.accountsFrom(SignupSource.OPENAI));
  }

  @Test
  void shouldRefuseToStoreASignupSourceOutsideTheKnownVendors() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.sql("INSERT INTO account (name, signup_source) VALUES ('Forged', 'ai:evil')")
                    .update())
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
