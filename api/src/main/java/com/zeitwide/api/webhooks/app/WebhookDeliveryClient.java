package com.zeitwide.api.webhooks.app;

import com.zeitwide.api.webhooks.domain.ResolvedWebhookTarget;
import com.zeitwide.api.webhooks.domain.WebhookEnvelope;

public interface WebhookDeliveryClient {

  int send(ResolvedWebhookTarget target, WebhookEnvelope envelope, String secret);
}
