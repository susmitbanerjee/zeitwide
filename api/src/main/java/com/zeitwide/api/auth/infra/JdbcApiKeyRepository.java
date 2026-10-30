package com.zeitwide.api.auth.infra;

import com.zeitwide.api.auth.app.ApiKeyRepository;
import com.zeitwide.api.auth.domain.ApiKeyMetadata;
import com.zeitwide.api.auth.domain.CreatedApiKeyRecord;
import com.zeitwide.api.auth.domain.StoredApiKey;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcApiKeyRepository implements ApiKeyRepository {

  // Served by the unique index on api_key.prefix.
  static final String ACTIVE_BY_PREFIX =
      """
      SELECT id, account_id, secret_sha256 FROM api_key
      WHERE prefix = :prefix AND revoked_at IS NULL
      """;

  private final JdbcClient jdbc;

  JdbcApiKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<StoredApiKey> findActiveByPrefix(String prefix) {
    return jdbc.sql(ACTIVE_BY_PREFIX)
        .param("prefix", prefix)
        .query(
            (rs, rowNum) ->
                new StoredApiKey(
                    rs.getObject("id", UUID.class),
                    rs.getObject("account_id", UUID.class),
                    HexFormat.of().formatHex(rs.getBytes("secret_sha256"))))
        .optional();
  }

  @Override
  public boolean recordUse(UUID id) {
    return jdbc.sql("UPDATE api_key SET last_used_at = now() WHERE id = :id AND revoked_at IS NULL")
            .param("id", id)
            .update()
        == 1;
  }

  @Override
  public void lockForAccount(UUID accountId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0)) IS NULL")
        .param("lock_key", "api-key:" + accountId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public long countForAccount(UUID accountId) {
    return jdbc.sql("SELECT count(*) FROM api_key WHERE account_id = :account")
        .param("account", accountId)
        .query(Long.class)
        .single();
  }

  @Override
  public Optional<CreatedApiKeyRecord> create(
      UUID accountId,
      String name,
      String prefix,
      byte[] secretHash,
      UUID createdBy,
      byte[] idempotencyHash) {
    return jdbc.sql(
            """
            INSERT INTO api_key
                (account_id, name, prefix, secret_sha256, created_by, idempotency_key_hash)
            VALUES (:account, :name, :prefix, :hash, :createdBy, :idempotencyHash)
            ON CONFLICT (account_id, idempotency_key_hash)
                WHERE idempotency_key_hash IS NOT NULL
                DO NOTHING
            RETURNING id, created_at
            """)
        .param("account", accountId)
        .param("name", name)
        .param("prefix", prefix)
        .param("hash", secretHash)
        .param("createdBy", createdBy)
        .param("idempotencyHash", idempotencyHash)
        .query(
            (rs, row) ->
                new CreatedApiKeyRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  @Override
  public List<ApiKeyMetadata> listForAccount(UUID accountId) {
    return jdbc.sql(
            "SELECT id, name, prefix, created_at, last_used_at, revoked_at "
                + "FROM api_key WHERE account_id = :account ORDER BY created_at DESC, id DESC LIMIT 100")
        .param("account", accountId)
        .query(
            (rs, row) ->
                new ApiKeyMetadata(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("prefix"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    instantOrNull(rs.getObject("last_used_at", OffsetDateTime.class)),
                    instantOrNull(rs.getObject("revoked_at", OffsetDateTime.class))))
        .list();
  }

  @Override
  public boolean revoke(UUID accountId, UUID keyId) {
    return jdbc.sql(
                "UPDATE api_key SET revoked_at = now() "
                    + "WHERE id = :id AND account_id = :account AND revoked_at IS NULL")
            .param("id", keyId)
            .param("account", accountId)
            .update()
        == 1;
  }

  private static java.time.Instant instantOrNull(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
