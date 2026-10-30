package com.zeitwide.api.receipts.infra;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("zeitwide.receipts")
public record ReceiptProperties(
    @NotNull String activeKeyId, @NotNull String signingKeyPem, @NotNull String publicKeysJson) {}
