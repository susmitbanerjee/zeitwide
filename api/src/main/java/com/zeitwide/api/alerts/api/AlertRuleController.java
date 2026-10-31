package com.zeitwide.api.alerts.api;

import com.zeitwide.api.alerts.app.AlertRuleService;
import com.zeitwide.api.auth.domain.AccountPrincipal;
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
@RequestMapping("/v1/account/alert-rules")
class AlertRuleController {

  private final AlertRuleService rules;

  AlertRuleController(AlertRuleService rules) {
    this.rules = rules;
  }

  @GetMapping
  List<AlertRuleDtos.Rule> list(@AuthenticationPrincipal AccountPrincipal principal) {
    return rules.list(principal).stream().map(AlertRuleDtos.Rule::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  AlertRuleDtos.Rule create(
      @AuthenticationPrincipal AccountPrincipal principal,
      @Valid @RequestBody AlertRuleDtos.Configuration body,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return AlertRuleDtos.Rule.from(rules.create(principal, body.request(), idempotencyKey));
  }

  @PutMapping("/{id}")
  AlertRuleDtos.Rule update(
      @AuthenticationPrincipal AccountPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody AlertRuleDtos.Configuration body) {
    return AlertRuleDtos.Rule.from(rules.update(principal, id, body.request()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
    rules.delete(principal, id);
  }
}
