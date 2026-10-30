package com.zeitwide.api.webhooks.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.webhooks.app.WebhookDeliveryClient;
import com.zeitwide.api.webhooks.app.WebhookDeliveryException;
import com.zeitwide.api.webhooks.app.WebhookSignature;
import com.zeitwide.api.webhooks.domain.ResolvedWebhookTarget;
import com.zeitwide.api.webhooks.domain.WebhookEnvelope;
import java.io.IOException;
import java.time.Clock;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/** One-shot HTTP client: the URI host is retained for TLS verification while DNS is pinned. */
final class ApacheWebhookDeliveryClient implements WebhookDeliveryClient {

  private static final int TIMEOUT_SECONDS = 5;
  private final ObjectMapper json;
  private final Clock clock;

  ApacheWebhookDeliveryClient(ObjectMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
  }

  @Override
  public int send(ResolvedWebhookTarget target, WebhookEnvelope envelope, String secret) {
    byte[] body;
    try {
      body = json.writeValueAsBytes(envelope);
    } catch (JsonProcessingException failure) {
      throw new WebhookDeliveryException("serialization_error", failure);
    }
    long timestamp = clock.instant().getEpochSecond();
    var connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new PinnedDnsResolver(target))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(TIMEOUT_SECONDS))
                    .setSocketTimeout(Timeout.ofSeconds(TIMEOUT_SECONDS))
                    .build())
            .setMaxConnTotal(1)
            .setMaxConnPerRoute(1)
            .build();
    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(TIMEOUT_SECONDS))
            .setResponseTimeout(Timeout.ofSeconds(TIMEOUT_SECONDS))
            .build();
    try (CloseableHttpClient client =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .build()) {
      var request = new org.apache.hc.client5.http.classic.methods.HttpPost(target.uri());
      request.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
      request.setHeader("Zeitwide-Event-Id", envelope.id());
      request.setHeader("Zeitwide-Signature", WebhookSignature.header(secret, timestamp, body));
      request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
      return client.execute(request, response -> response.getCode());
    } catch (IOException failure) {
      throw new WebhookDeliveryException("network_error", failure);
    }
  }
}
