package com.zeitwide.api.accounts.app;

import com.zeitwide.api.accounts.domain.AccountIdentity;
import java.util.Optional;

/** Persistence boundary for Keycloak-subject account provisioning. */
public interface AccountRepository {

  void lockSubject(String subject);

  Optional<AccountIdentity> findBySubject(String subject);

  AccountIdentity createForSubject(String subject, String accountName);
}
