package com.zeitwide.api.webhooks.api;

import com.zeitwide.api.auth.domain.AccountPrincipal;
import com.zeitwide.api.webhooks.app.WebhookSubscriptionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/account/webhooks")
public class WebhookController {

  private final WebhookSubscriptionService webhooks;

  public WebhookController(WebhookSubscriptionService webhooks) {
    this.webhooks = webhooks;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  WebhookDtos.Created create(
      @AuthenticationPrincipal AccountPrincipal principal,
      @Valid @RequestBody WebhookDtos.Configuration body,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return WebhookDtos.Created.from(
        webhooks.create(principal, body.url(), body.events(), idempotencyKey));
  }

  @GetMapping
  List<WebhookDtos.Summary> list(@AuthenticationPrincipal AccountPrincipal principal) {
    return webhooks.list(principal).stream().map(WebhookDtos.Summary::from).toList();
  }

  @PutMapping("/{id}")
  WebhookDtos.Summary update(
      @AuthenticationPrincipal AccountPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody WebhookDtos.Configuration body) {
    return WebhookDtos.Summary.from(webhooks.update(principal, id, body.url(), body.events()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
    webhooks.delete(principal, id);
  }

  @PostMapping("/{id}/test")
  WebhookDtos.TestResult test(
      @AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
    return WebhookDtos.TestResult.from(webhooks.sendTest(principal, id));
  }
}
