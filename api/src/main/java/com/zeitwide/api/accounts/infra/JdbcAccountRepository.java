package com.zeitwide.api.accounts.infra;

import com.zeitwide.api.accounts.app.AccountRepository;
import com.zeitwide.api.accounts.domain.AccountIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

  private final JdbcClient jdbc;

  JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockSubject(String subject) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:subject, 0)) IS NULL")
        .param("subject", subject)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<AccountIdentity> findBySubject(String subject) {
    return jdbc.sql("SELECT id, account_id FROM app_user WHERE keycloak_subject = :subject")
        .param("subject", subject)
        .query(
            (rs, row) ->
                new AccountIdentity(
                    rs.getObject("account_id", UUID.class), rs.getObject("id", UUID.class)))
        .optional();
  }

  @Override
  public AccountIdentity createForSubject(String subject, String accountName) {
    UUID accountId =
        jdbc.sql("INSERT INTO account (name, plan) VALUES (:name, 'free') RETURNING id")
            .param("name", accountName)
            .query(UUID.class)
            .single();
    UUID appUserId =
        jdbc.sql(
                "INSERT INTO app_user (account_id, keycloak_subject, role) "
                    + "VALUES (:account, :subject, 'owner') RETURNING id")
            .param("account", accountId)
            .param("subject", subject)
            .query(UUID.class)
            .single();
    return new AccountIdentity(accountId, appUserId);
  }
}
