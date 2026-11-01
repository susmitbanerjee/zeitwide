package com.zeitwide.api.auth.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Stops one address from guessing API keys. Every key check reads the database, whose small pool
 * serves all callers, so an address that keeps presenting keys that don't work is turned away
 * before the lookup. Counts live in this instance's memory: it is a guard for the pool, not an
 * exact limit, and a restart or a second instance simply starts counting again.
 */
@Component
public class InvalidKeyThrottle {

  static final int MAX_FAILURES = 30;
  static final Duration WINDOW = Duration.ofMinutes(5);
  private static final int MAX_ADDRESSES = 50_000;

  private final Cache<String, AtomicInteger> failures =
      Caffeine.newBuilder().maximumSize(MAX_ADDRESSES).expireAfterWrite(WINDOW).build();

  /** Whether {@code clientAddress} has used up its failed attempts for the current window. */
  public boolean isBlocked(String clientAddress) {
    AtomicInteger count = failures.getIfPresent(clientAddress);
    return count != null && count.get() >= MAX_FAILURES;
  }

  /** Counts one key that was malformed, unknown or revoked. The window starts at the first. */
  public void recordFailure(String clientAddress) {
    failures.get(clientAddress, address -> new AtomicInteger()).incrementAndGet();
  }

  /** Seconds a blocked caller should wait: the window is fixed, so this is its upper bound. */
  public static long retryAfterSeconds() {
    return WINDOW.toSeconds();
  }
}
