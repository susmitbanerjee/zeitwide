package com.zeitwide.api.ratelimits.infra;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class Bucket4jConfig {

  // A bucket that has been full this long is dropped by cleanup_old_rows (V8).
  private static final Duration KEEP_AFTER_FULL = Duration.ofMinutes(10);

  /**
   * Advisory-lock based, so concurrent requests for one bucket queue on a Postgres lock instead of
   * racing. The table layout is V7 plus the lock column from V14.
   */
  @Bean
  ProxyManager<Long> rateLimitBuckets(DataSource dataSource) {
    return Bucket4jPostgreSQL.advisoryLockBasedBuilder(dataSource)
        .table("rate_limit_bucket")
        .idColumn("id")
        .stateColumn("state")
        .expiresAtColumn("expires_at")
        .lockColumn("explicit_lock")
        .expirationAfterWrite(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
        .build();
  }
}
