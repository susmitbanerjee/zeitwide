package com.zeitwide.api.auth.app;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("zeitwide.internal")
public record InternalAuthProperties(
    @NotBlank String audience,
    @NotBlank String serviceAccountEmail,
    @NotBlank String googleJwkSetUri) {}
