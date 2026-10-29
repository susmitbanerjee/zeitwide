package com.settlecal.api.auth.app;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * API key checks.
 *
 * @param cacheTtl how long a key check is remembered; also how long a revoked key keeps working
 */
@Validated
@ConfigurationProperties("settlecal.api-keys")
public record ApiKeyProperties(@NotNull Duration cacheTtl) {}
