package com.zeitwide.api.webhooks.app;

import com.zeitwide.api.webhooks.domain.ResolvedWebhookTarget;
import java.net.URI;

public interface WebhookTargetGuard {

  ResolvedWebhookTarget resolveAndValidate(URI uri);
}
