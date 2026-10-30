package com.zeitwide.api.webhooks.infra;

import com.zeitwide.api.webhooks.app.WebhookDeliveryClient;
import com.zeitwide.api.webhooks.app.WebhookSecretCipher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
class WebhookConfiguration {

  @Bean
  @ConditionalOnMissingBean(WebhookSecretCipher.class)
  WebhookSecretCipher webhookSecretCipher(WebhookProperties properties) {
    if (properties.encryptionKey() == null || properties.encryptionKey().isBlank()) {
      throw new IllegalStateException("ZEITWIDE_WEBHOOK_ENCRYPTION_KEY is required");
    }
    return new AesGcmWebhookSecretCipher(properties.encryptionKey());
  }

  @Bean
  @ConditionalOnMissingBean(WebhookDeliveryClient.class)
  WebhookDeliveryClient webhookDeliveryClient(
      com.fasterxml.jackson.databind.ObjectMapper json, java.time.Clock clock) {
    return new ApacheWebhookDeliveryClient(json, clock);
  }
}
