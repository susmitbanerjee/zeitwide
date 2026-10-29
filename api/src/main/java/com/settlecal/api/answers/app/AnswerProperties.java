package com.settlecal.api.answers.app;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Answer caching.
 *
 * @param cacheMaxEntries answers kept in memory; each is small and a release never changes
 * @param releaseCheckInterval how often to look for a newer dataset release
 */
@Validated
@ConfigurationProperties("settlecal.answers")
public record AnswerProperties(
    @Min(1) @Max(1_000_000) long cacheMaxEntries, @NotNull Duration releaseCheckInterval) {}
