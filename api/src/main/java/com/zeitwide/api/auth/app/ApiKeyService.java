package com.zeitwide.api.auth.app;

import com.zeitwide.api.auth.domain.AccountPrincipal;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.auth.domain.CreatedApiKeyRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
  private static final int MAX_KEYS_PER_ACCOUNT = 100;
  private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 16;
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
  private final ApiKeyRepository keys;
  private final ApiKeyGenerator generator;
  private final Entitlements entitlements;

  ApiKeyService(ApiKeyRepository keys, Entitlements entitlements) {
    this.keys = keys;
    this.generator = new ApiKeyGenerator();
    this.entitlements = entitlements;
  }

  @Transactional
  public CreatedApiKey create(AccountPrincipal principal, String name, String idempotencyKey) {
    entitlements.check(Entitlements.Feature.API_KEYS);
    String safeName = name.trim();
    keys.lockForAccount(principal.accountId());
    if (keys.countForAccount(principal.accountId()) >= MAX_KEYS_PER_ACCOUNT) {
      throw new ApiKeyLimitExceededException();
    }
    byte[] idempotencyHash = idempotencyHash(idempotencyKey);
    ApiKeyToken token = generator.generate();
    CreatedApiKeyRecord created =
        keys.create(
                principal.accountId(),
                safeName,
                token.prefix(),
                token.secretSha256(),
                principal.appUserId(),
                idempotencyHash)
            .orElseThrow(IdempotencyKeyConflictException::new);
    log.atInfo()
        .addKeyValue("event", "api_key_created")
        .addKeyValue("account_id", principal.accountId())
        .addKeyValue("api_key_id", created.id())
        .addKeyValue("prefix", token.prefix())
        .log("API key created");
    return new CreatedApiKey(
        created.id(), safeName, token.prefix(), token.value(), created.createdAt());
  }

  public List<ApiKeySummary> list(AccountPrincipal principal) {
    entitlements.check(Entitlements.Feature.API_KEYS);
    return keys.listForAccount(principal.accountId()).stream()
        .map(
            key ->
                new ApiKeySummary(
                    key.id(),
                    key.name(),
                    key.prefix(),
                    key.createdAt(),
                    key.lastUsedAt(),
                    key.revokedAt()))
        .toList();
  }

  @Transactional
  public void revoke(AccountPrincipal principal, UUID id) {
    entitlements.check(Entitlements.Feature.API_KEYS);
    if (keys.revoke(principal.accountId(), id)) {
      log.atInfo()
          .addKeyValue("event", "api_key_revoked")
          .addKeyValue("account_id", principal.accountId())
          .addKeyValue("api_key_id", id)
          .log("API key revoked");
    }
  }

  private static byte[] idempotencyHash(String idempotencyKey) {
    if (idempotencyKey == null) {
      return null;
    }
    if (idempotencyKey.length() < MIN_IDEMPOTENCY_KEY_LENGTH
        || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new InvalidIdempotencyKeyException();
    }
    return ApiKeyToken.sha256(idempotencyKey);
  }

  public record CreatedApiKey(UUID id, String name, String prefix, String key, Instant createdAt) {
    @Override
    public String toString() {
      return "CreatedApiKey[id="
          + id
          + ", name="
          + name
          + ", prefix="
          + prefix
          + ", key=<redacted>]";
    }
  }

  public record ApiKeySummary(
      UUID id,
      String name,
      String prefix,
      Instant createdAt,
      Instant lastUsedAt,
      Instant revokedAt) {}
}
