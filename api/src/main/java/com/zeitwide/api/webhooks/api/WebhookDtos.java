package com.zeitwide.api.webhooks.api;

import com.zeitwide.api.webhooks.app.WebhookSubscriptionService;
import com.zeitwide.api.webhooks.domain.WebhookSubscription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.UniqueElements;

final class WebhookDtos {

  static final String EVENT_TYPE = "closure_added|closure_removed|closure_changed|basis_changed";

  private WebhookDtos() {}

  record Configuration(
      @NotBlank @Size(max = 2048) String url,
      @NotNull @Size(min = 1, max = 16) @UniqueElements
          List<@NotNull @Size(max = 32) @Pattern(regexp = EVENT_TYPE) String> events) {}

  record Created(UUID id, String url, List<String> events, String secret, Instant createdAt) {
    static Created from(WebhookSubscriptionService.CreatedWebhook webhook) {
      return new Created(
          webhook.id(), webhook.url(), webhook.events(), webhook.secret(), webhook.createdAt());
    }

    @Override
    public String toString() {
      return "Created[id=" + id + ", url=<redacted>, events=" + events + ", secret=<redacted>]";
    }
  }

  record Summary(UUID id, String url, List<String> events, Instant createdAt) {
    static Summary from(WebhookSubscription webhook) {
      return new Summary(webhook.id(), webhook.url(), webhook.events(), webhook.createdAt());
    }
  }

  record TestResult(boolean succeeded, Integer responseStatus) {
    static TestResult from(WebhookSubscriptionService.TestResult result) {
      return new TestResult(result.succeeded(), result.responseStatus());
    }
  }
}
