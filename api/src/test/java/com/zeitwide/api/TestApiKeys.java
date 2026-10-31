package com.zeitwide.api;

import com.zeitwide.api.auth.domain.ApiKeyToken;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Made-up API keys for integration tests, stored as the API stores real ones. */
public final class TestApiKeys {

  private TestApiKeys() {}

  /** A new account with one key; returns the full key for an Authorization header. */
  public static String create(JdbcClient jdbc) {
    String id = Integer.toString(Math.abs(UUID.randomUUID().hashCode() % 90_000_000) + 10_000_000);
    String prefix = "zw_live_" + id;
    String secret = UUID.randomUUID().toString().replace("-", "") + "abcdefghijk";
    UUID account =
        jdbc.sql("INSERT INTO account (name) VALUES ('Test') RETURNING id")
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO api_key (account_id, name, prefix, secret_sha256)
            VALUES (:account, 'test', :prefix, :hash)
            """)
        .param("account", account)
        .param("prefix", prefix)
        .param("hash", ApiKeyToken.sha256(secret))
        .update();
    return prefix + "_" + secret;
  }

  public static String bearer(String key) {
    return "Bearer " + key;
  }
}
