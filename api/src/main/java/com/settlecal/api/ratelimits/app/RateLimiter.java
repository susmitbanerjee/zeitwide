package com.settlecal.api.ratelimits.app;

import com.settlecal.api.ratelimits.domain.Allowance;
import com.settlecal.api.ratelimits.domain.Limit;

/** Shared rate-limit state, so every API instance counts the same requests. */
public interface RateLimiter {

  /** Takes one request from the bucket {@code bucketId}, which follows {@code limit}. */
  Allowance tryConsume(long bucketId, Limit limit);
}
