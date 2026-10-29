package com.settlecal.api.ratelimits.infra;

import com.settlecal.api.ratelimits.app.RateLimiter;
import com.settlecal.api.ratelimits.domain.Allowance;
import com.settlecal.api.ratelimits.domain.Limit;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Token buckets in Postgres ({@code rate_limit_bucket}), shared by every API instance. */
@Component
class Bucket4jRateLimiter implements RateLimiter {

  private final ProxyManager<Long> buckets;
  private final Map<Limit, BucketConfiguration> configurations = new ConcurrentHashMap<>();

  Bucket4jRateLimiter(ProxyManager<Long> buckets) {
    this.buckets = buckets;
  }

  @Override
  public Allowance tryConsume(long bucketId, Limit limit) {
    BucketConfiguration configuration =
        configurations.computeIfAbsent(limit, Bucket4jRateLimiter::configuration);
    ConsumptionProbe probe =
        buckets.builder().build(bucketId, () -> configuration).tryConsumeAndReturnRemaining(1);
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
