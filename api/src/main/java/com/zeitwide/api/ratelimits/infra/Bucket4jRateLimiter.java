package com.zeitwide.api.ratelimits.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zeitwide.api.ratelimits.app.RateLimiter;
import com.zeitwide.api.ratelimits.domain.Allowance;
import com.zeitwide.api.ratelimits.domain.Limit;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.optimization.Optimizations;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Token buckets in Postgres ({@code rate_limit_bucket}), shared by every API instance.
 *
 * <p>Each bucket's proxy is kept and batched: concurrent requests on one busy key merge into one
 * database call instead of queueing on its advisory lock while holding pool connections. Limits
 * stay exact.
 */
@Component
class Bucket4jRateLimiter implements RateLimiter {

  private static final int MAX_CACHED_BUCKETS = 10_000;
  private static final Duration IDLE_BUCKET_EXPIRY = Duration.ofMinutes(10);

  private final ProxyManager<Long> buckets;
  private final Map<Limit, BucketConfiguration> configurations = new ConcurrentHashMap<>();
  private final Cache<BucketKey, BucketProxy> proxies =
      Caffeine.newBuilder()
          .maximumSize(MAX_CACHED_BUCKETS)
          .expireAfterAccess(IDLE_BUCKET_EXPIRY)
          .build();

  Bucket4jRateLimiter(ProxyManager<Long> buckets) {
    this.buckets = buckets;
  }

  private record BucketKey(long bucketId, Limit limit) {}

  @Override
  public Allowance tryConsume(long bucketId, Limit limit) {
    BucketConfiguration configuration =
        configurations.computeIfAbsent(limit, Bucket4jRateLimiter::configuration);
    BucketProxy bucket =
        proxies.get(
            new BucketKey(bucketId, limit),
            key ->
                buckets
                    .builder()
                    .withOptimization(Optimizations.batching())
                    .build(bucketId, () -> configuration));
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return new Allowance.Allowed(limit.capacity(), probe.getRemainingTokens());
    }
    return new Allowance.Denied(
        limit.capacity(), Duration.ofNanos(probe.getNanosToWaitForRefill()));
  }

  private static BucketConfiguration configuration(Limit limit) {
    return BucketConfiguration.builder()
        .addLimit(
            bandwidth ->
                bandwidth.capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()))
        .build();
  }
}
