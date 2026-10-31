package com.zeitwide.api.alerts.app;

import com.zeitwide.api.alerts.domain.AlertRule;
import com.zeitwide.api.alerts.domain.AlertRuleSettings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Alert rules, always scoped to one account. */
public interface AlertRuleRepository {

  /** Serializes rule creation per account, so the limit check can't race. */
  void lockForAccount(UUID accountId);

  long countForAccount(UUID accountId);

  List<AlertRule> listForAccount(UUID accountId, int limit);

  /** Empty when the account already used this idempotency key. */
  Optional<AlertRule> create(UUID accountId, AlertRuleSettings settings, byte[] idempotencyHash);

  /** Empty when the account has no rule with this ID. */
  Optional<AlertRule> update(UUID accountId, UUID id, AlertRuleSettings settings);

  boolean delete(UUID accountId, UUID id);
}
