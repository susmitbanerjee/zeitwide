package com.zeitwide.api.ratelimits.app;

import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.auth.domain.Caller;
import com.zeitwide.api.ratelimits.domain.Allowance;
import com.zeitwide.api.ratelimits.domain.ClientNetwork;
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
          limiter.tryConsume(
              bucketId("key:" + keyed.apiKeyId()),
              properties.firstPartyKeyIds().contains(keyed.apiKeyId())
                  ? properties.firstParty()
                  : properties.keyed());
      case Caller.Authenticated authenticated ->
          limiter.tryConsume(bucketId("account:" + authenticated.accountId()), properties.keyed());
    };
  }

  /**
   * One answer for an anonymous agent: an MCP tool call or an A2A message. Counted by network, so
   * the daily allowance is per /24 (IPv4) or /48 (IPv6), not per address.
   */
  public Allowance consumeAnonymousCall(Caller.Anonymous caller) {
    return limiter.tryConsume(
        bucketId("anonymous-call:" + ClientNetwork.of(caller.clientAddress())),
        properties.anonymousCalls());
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
