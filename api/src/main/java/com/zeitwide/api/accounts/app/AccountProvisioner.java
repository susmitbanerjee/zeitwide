package com.zeitwide.api.accounts.app;

import com.zeitwide.api.accounts.domain.AccountIdentity;
import com.zeitwide.api.accounts.domain.SignupSource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the local account and owner membership on the first validated sign-in. */
@Service
public class AccountProvisioner {

  private final AccountRepository accounts;

  AccountProvisioner(AccountRepository accounts) {
    this.accounts = accounts;
  }

  /**
   * The account for this subject, created on first sign-in. The signup source is kept only when the
   * account is created, and only when it is exactly one of {@link SignupSource}'s values; anything
   * else is ignored rather than refused, because it is attribution, not identity.
   */
  @Transactional
  public AccountIdentity resolve(
      String subject, String requestedName, @Nullable String requestedSignupSource) {
    if (subject == null || subject.isBlank() || subject.length() > 255) {
      throw new IllegalArgumentException("The identity token has no usable subject");
    }
    accounts.lockSubject(subject);
    return accounts
        .findBySubject(subject)
        .orElseGet(
            () ->
                accounts.createForSubject(
                    subject,
                    safeName(requestedName),
                    SignupSource.fromValue(requestedSignupSource).orElse(null)));
  }

  private static String safeName(String requestedName) {
    if (requestedName == null || requestedName.isBlank()) {
      return "Zeitwide account";
    }
    String trimmed = requestedName.trim();
    return trimmed.substring(0, Math.min(trimmed.length(), 200));
  }
}
