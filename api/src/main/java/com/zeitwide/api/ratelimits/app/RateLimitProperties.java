package com.zeitwide.api.ratelimits.app;

import com.zeitwide.api.ratelimits.domain.Limit;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Request limits.
 *
 * @param anonymous per client address, for requests without an API key
 * @param keyed per API key
 * @param firstParty per API key for Zeitwide's own services (the web app prerendering answer
 *     pages), listed by key ID in {@code firstPartyKeyIds}
 * @param trustedProxies proxies in front of the API that append to X-Forwarded-For: 0 locally (use
 *     the connection's address), 1 on Cloud Run (its front end appends the client's)
 */
@Validated
@ConfigurationProperties("zeitwide.rate-limits")
public record RateLimitProperties(
    @NotNull Limit anonymous,
    @NotNull Limit keyed,
    @NotNull Limit firstParty,
    @NotNull @Size(max = 10) List<UUID> firstPartyKeyIds,
    @Min(0) @Max(5) int trustedProxies) {

  public RateLimitProperties {
    firstPartyKeyIds = List.copyOf(firstPartyKeyIds);
  }
}
