package com.zeitwide.api.ratelimits.app;

import com.zeitwide.api.ratelimits.domain.Limit;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Request limits.
 *
 * @param anonymous per client address, for requests without an API key
 * @param keyed per API key
 * @param trustedProxies proxies in front of the API that append to X-Forwarded-For: 0 locally (use
 *     the connection's address), 1 on Cloud Run (its front end appends the client's)
 */
@Validated
@ConfigurationProperties("zeitwide.rate-limits")
public record RateLimitProperties(
    @NotNull Limit anonymous, @NotNull Limit keyed, @Min(0) @Max(5) int trustedProxies) {}
