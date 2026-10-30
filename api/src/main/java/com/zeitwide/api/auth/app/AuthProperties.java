package com.zeitwide.api.auth.app;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("zeitwide.auth")
public record AuthProperties(
    @NotBlank String issuerUri, @NotBlank String jwkSetUri, @NotBlank String audience) {}
