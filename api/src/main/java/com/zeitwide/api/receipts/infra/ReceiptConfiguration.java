package com.zeitwide.api.receipts.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.receipts.app.ReceiptCrypto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReceiptProperties.class)
class ReceiptConfiguration {

  @Bean
  @ConditionalOnMissingBean(ReceiptCrypto.class)
  ReceiptCrypto receiptCrypto(ReceiptProperties properties, ObjectMapper json) {
    return new Ed25519ReceiptCrypto(
        properties.activeKeyId(),
        properties.signingKeyPem(),
        Ed25519ReceiptCrypto.parsePublicKeys(properties.publicKeysJson(), json));
  }
}
