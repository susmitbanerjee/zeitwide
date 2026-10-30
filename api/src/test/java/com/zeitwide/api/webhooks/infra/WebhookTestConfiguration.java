package com.zeitwide.api.webhooks.infra;

import com.zeitwide.api.webhooks.app.WebhookDeliveryClient;
import com.zeitwide.api.webhooks.app.WebhookSecretCipher;
import com.zeitwide.api.webhooks.app.WebhookTargetGuard;
import com.zeitwide.api.webhooks.domain.ResolvedWebhookTarget;
import java.net.InetAddress;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Fake outbound network and non-production key material for Spring integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class WebhookTestConfiguration {

  private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Bean
  @Primary
  WebhookSecretCipher testWebhookSecretCipher() {
    return new AesGcmWebhookSecretCipher(TEST_KEY);
  }

  @Bean
  @Primary
  WebhookTargetGuard testWebhookTargetGuard() throws java.net.UnknownHostException {
    InetAddress publicAddress = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
    return uri -> new ResolvedWebhookTarget(uri, List.of(publicAddress));
  }

  @Bean
  @Primary
  WebhookDeliveryClient testWebhookDeliveryClient() {
    return (target, envelope, secret) -> 204;
  }
}
