package com.zeitwide.api.auth.app;

import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.auth.domain.Caller;
import com.zeitwide.api.auth.domain.StoredApiKey;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Checks API keys: find by prefix, compare the secret's SHA-256 in constant time, and record use.
 * Positive results are never cached, so revocation takes effect on the next request.
 */
@Service
public class ApiKeyAuthenticator {

  private final ApiKeyRepository keys;

  ApiKeyAuthenticator(ApiKeyRepository keys) {
    this.keys = keys;
  }

  /** The caller a bearer token belongs to, or empty if it is malformed, unknown or revoked. */
  public Optional<Caller.Keyed> authenticate(String token) {
    return check(token);
  }

  private Optional<Caller.Keyed> check(String token) {
    return ApiKeyToken.parse(token)
        .flatMap(
            parsed ->
                keys.findActiveByPrefix(parsed.prefix())
                    .filter(k -> matches(k, parsed))
                    .filter(k -> keys.recordUse(k.id())))
        .map(stored -> new Caller.Keyed(stored.id(), stored.accountId()));
  }

  private static boolean matches(StoredApiKey stored, ApiKeyToken token) {
    return MessageDigest.isEqual(
        HexFormat.of().parseHex(stored.secretSha256Hex()), token.secretSha256());
  }
}
