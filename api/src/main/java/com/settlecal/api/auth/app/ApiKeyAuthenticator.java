package com.settlecal.api.auth.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.settlecal.api.auth.domain.ApiKeyToken;
import com.settlecal.api.auth.domain.Caller;
import com.settlecal.api.auth.domain.StoredApiKey;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Checks API keys: find by prefix, then compare the secret's SHA-256 in constant time. Results
 * (good and bad) are remembered briefly, keyed by the token's hash so no secret sits in memory,
 * which also keeps guessing from reaching the database.
 */
@Service
@EnableConfigurationProperties(ApiKeyProperties.class)
public class ApiKeyAuthenticator {

  private static final int MAX_REMEMBERED = 10_000;

  private final ApiKeyRepository keys;
  private final Cache<String, Optional<Caller.Keyed>> checked;

  ApiKeyAuthenticator(ApiKeyRepository keys, ApiKeyProperties properties) {
    this.keys = keys;
    this.checked =
        Caffeine.newBuilder()
            .maximumSize(MAX_REMEMBERED)
            .expireAfterWrite(properties.cacheTtl())
            .build();
  }

  /** The caller a bearer token belongs to, or empty if it is malformed, unknown or revoked. */
  public Optional<Caller.Keyed> authenticate(String token) {
    String fingerprint = HexFormat.of().formatHex(ApiKeyToken.sha256(token));
    return checked.get(fingerprint, key -> check(token));
  }

  private Optional<Caller.Keyed> check(String token) {
    return ApiKeyToken.parse(token)
        .flatMap(parsed -> keys.findActiveByPrefix(parsed.prefix()).filter(k -> matches(k, parsed)))
        .map(stored -> new Caller.Keyed(stored.id(), stored.accountId()));
  }

  private static boolean matches(StoredApiKey stored, ApiKeyToken token) {
    return MessageDigest.isEqual(
        HexFormat.of().parseHex(stored.secretSha256Hex()), token.secretSha256());
  }
}
