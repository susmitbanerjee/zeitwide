package com.settlecal.api.ratelimits.app;

import com.settlecal.api.auth.domain.ApiKeyToken;
import com.settlecal.api.auth.domain.Caller;
import com.settlecal.api.ratelimits.domain.Allowance;
import java.nio.ByteBuffer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** Anonymous callers share a small limit per address; each API key gets a larger one. */
@Service
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimits {

  private final RateLimiter limiter;
  private final RateLimitProperties properties;

  RateLimits(RateLimiter limiter, RateLimitProperties properties) {
    this.limiter = limiter;
    this.properties = properties;
  }

  public Allowance consume(Caller caller) {
    return switch (caller) {
      case Caller.Anonymous anonymous ->
          limiter.tryConsume(
              bucketId("anonymous:" + anonymous.clientAddress()), properties.anonymous());
      case Caller.Keyed keyed ->
          limiter.tryConsume(bucketId("key:" + keyed.apiKeyId()), properties.keyed());
    };
  }

  public int trustedProxies() {
    return properties.trustedProxies();
  }

  /**
   * The table keys buckets by a 64-bit ID. Hashing keeps addresses out of the table; at most a few
   * million distinct callers make a collision vanishingly unlikely.
   */
  static long bucketId(String identity) {
    return ByteBuffer.wrap(ApiKeyToken.sha256(identity)).getLong();
  }
}
