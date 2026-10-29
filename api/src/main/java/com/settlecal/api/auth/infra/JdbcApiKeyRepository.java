package com.settlecal.api.auth.infra;

import com.settlecal.api.auth.app.ApiKeyRepository;
import com.settlecal.api.auth.domain.StoredApiKey;
import java.util.HexFormat;
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
}
