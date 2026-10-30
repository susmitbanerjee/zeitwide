package com.zeitwide.api.accounts.app;

import com.zeitwide.api.accounts.domain.AccountIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the local account and owner membership on the first validated Keycloak login. */
@Service
public class AccountProvisioner {

  private final AccountRepository accounts;

  AccountProvisioner(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional
  public AccountIdentity resolve(String subject, String requestedName) {
    if (subject == null || subject.isBlank() || subject.length() > 255) {
      throw new IllegalArgumentException("The identity token has no usable subject");
    }
    accounts.lockSubject(subject);
    return accounts
        .findBySubject(subject)
        .orElseGet(() -> accounts.createForSubject(subject, safeName(requestedName)));
  }

  private static String safeName(String requestedName) {
    if (requestedName == null || requestedName.isBlank()) {
      return "Zeitwide account";
    }
    String trimmed = requestedName.trim();
    return trimmed.substring(0, Math.min(trimmed.length(), 200));
  }
}
