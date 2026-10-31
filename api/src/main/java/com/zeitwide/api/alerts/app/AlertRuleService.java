package com.zeitwide.api.alerts.app;

import com.zeitwide.api.alerts.domain.AlertRule;
import com.zeitwide.api.alerts.domain.AlertRuleSettings;
import com.zeitwide.api.auth.app.Entitlements;
import com.zeitwide.api.auth.app.IdempotencyKeyConflictException;
import com.zeitwide.api.auth.app.InvalidIdempotencyKeyException;
import com.zeitwide.api.auth.domain.AccountPrincipal;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.domain.ChangeType;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, lists, replaces and deletes an account's alert rules. */
@Service
public class AlertRuleService {

  static final int MAX_RULES_PER_ACCOUNT = 50;
  private static final int MIN_IDEMPOTENCY_KEY_LENGTH = 16;
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
  private static final Logger log = LoggerFactory.getLogger(AlertRuleService.class);

  private final AlertRuleRepository rules;
  private final Entitlements entitlements;

  public AlertRuleService(AlertRuleRepository rules, Entitlements entitlements) {
    this.rules = rules;
    this.entitlements = entitlements;
  }

  @Transactional(readOnly = true)
  public List<AlertRule> list(AccountPrincipal principal) {
    entitlements.check(Entitlements.Feature.ALERTS);
    return rules.listForAccount(principal.accountId(), MAX_RULES_PER_ACCOUNT);
  }

  @Transactional
  public AlertRule create(AccountPrincipal principal, Request request, String idempotencyKey) {
    entitlements.check(Entitlements.Feature.ALERTS);
    AlertRuleSettings settings = settings(request);
    byte[] idempotencyHash = idempotencyHash(idempotencyKey);
    rules.lockForAccount(principal.accountId());
    if (rules.countForAccount(principal.accountId()) >= MAX_RULES_PER_ACCOUNT) {
      throw new AlertRuleLimitExceededException();
    }
    AlertRule created =
        rules
            .create(principal.accountId(), settings, idempotencyHash)
            .orElseThrow(IdempotencyKeyConflictException::new);
    audit("alert_rule_created", principal, created.id());
    return created;
  }

  @Transactional
  public AlertRule update(AccountPrincipal principal, UUID id, Request request) {
    entitlements.check(Entitlements.Feature.ALERTS);
    AlertRule updated =
        rules
            .update(principal.accountId(), id, settings(request))
            .orElseThrow(AlertRuleNotFoundException::new);
    audit("alert_rule_updated", principal, id);
    return updated;
  }

  @Transactional
  public void delete(AccountPrincipal principal, UUID id) {
    entitlements.check(Entitlements.Feature.ALERTS);
    if (rules.delete(principal.accountId(), id)) {
      audit("alert_rule_deleted", principal, id);
    }
  }

  private static AlertRuleSettings settings(Request request) {
    try {
      return new AlertRuleSettings(
          request.name(),
          request.calendars().stream().map(CalendarId::new).toList(),
          request.events().stream().map(ChangeType::fromCode).toList(),
          request.maxNoticeDays());
    } catch (IllegalArgumentException invalid) {
      throw new InvalidAlertRuleException(invalid);
    }
  }

  private static byte[] idempotencyHash(String key) {
    if (key == null
        || key.length() < MIN_IDEMPOTENCY_KEY_LENGTH
        || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new InvalidIdempotencyKeyException();
    }
    return ApiKeyToken.sha256(key);
  }

  private static void audit(String event, AccountPrincipal principal, UUID ruleId) {
    log.atInfo()
        .addKeyValue("event", event)
        .addKeyValue("account_id", principal.accountId())
        .addKeyValue("alert_rule_id", ruleId)
        .log("Alert rule changed");
  }

  /** A rule as the caller sent it, before validation. */
  public record Request(
      String name, List<String> calendars, List<String> events, Integer maxNoticeDays) {
    public Request {
      calendars = List.copyOf(calendars);
      events = List.copyOf(events);
    }
  }
}
