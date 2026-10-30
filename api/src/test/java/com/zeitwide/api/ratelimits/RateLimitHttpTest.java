package com.zeitwide.api.ratelimits;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Limits and API keys on real Postgres, through Bucket4j. No release is loaded, so allowed requests
 * answer 503; only the limit matters here.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zeitwide.rate-limits.anonymous.capacity=2",
      "zeitwide.rate-limits.keyed.capacity=3",
      "zeitwide.auth.audience=zeitwide-api"
    })
@Import(PostgresTestcontainer.class)
class RateLimitHttpTest {

  private static final int UNAUTHORIZED = 401;
  private static final int TOO_MANY = 429;
  private static final int UNAVAILABLE = 503;

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;

  @Test
  void shouldLimitAnonymousCallersPerAddressInPostgres() throws Exception {
    // With no trusted proxies, a client-set X-Forwarded-For must not buy a fresh bucket.
    String spoofed = "203.0.113.9";
    assertThat(statusOf(null, null)).isNotEqualTo(TOO_MANY);
    assertThat(statusOf(null, spoofed)).isNotEqualTo(TOO_MANY);

    HttpResponse<String> limited = send(null, "198.51.100.7");

    assertThat(limited.statusCode()).isEqualTo(TOO_MANY);
    assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    assertThat(limited.body()).contains("\"code\":\"rate_limited\"");
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM rate_limit_bucket
                    WHERE expires_at IS NOT NULL AND explicit_lock IS NOT NULL
                    """)
                .query(Long.class)
                .single())
        .isPositive();
  }

  @Test
  void shouldGiveEachApiKeyItsOwnLimit() throws Exception {
    String key = createKey();

    for (int i = 0; i < 3; i++) {
      assertThat(statusOf(key, null)).isEqualTo(UNAVAILABLE);
    }
    assertThat(statusOf(key, null)).isEqualTo(TOO_MANY);
  }

  @Test
  void shouldRejectUnknownAndRevokedKeys() throws Exception {
    String key = createKey();
    jdbc.sql("UPDATE api_key SET revoked_at = now() WHERE prefix = :prefix")
        .param("prefix", key.substring(0, "sc_live_".length() + 8))
        .update();

    assertThat(statusOf(key, null)).isEqualTo(UNAUTHORIZED);
    assertThat(statusOf("sc_live_Zz999999_" + "x".repeat(43), null)).isEqualTo(UNAUTHORIZED);
  }

  private String createKey() {
    String id = Integer.toString(Math.abs(UUID.randomUUID().hashCode() % 90_000_000) + 10_000_000);
    String prefix = "sc_live_" + id;
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

  private int statusOf(String key, String forwardedFor) throws IOException, InterruptedException {
    return send(key, forwardedFor).statusCode();
  }

  private HttpResponse<String> send(String key, String forwardedFor)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/calendars"));
    if (key != null) {
      request.header("Authorization", "Bearer " + key);
    }
    if (forwardedFor != null) {
      request.header("X-Forwarded-For", forwardedFor);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
