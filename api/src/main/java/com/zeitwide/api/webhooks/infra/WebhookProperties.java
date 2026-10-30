package com.zeitwide.api.webhooks.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zeitwide.webhooks")
public record WebhookProperties(String encryptionKey) {}
