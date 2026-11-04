package com.zeitwide.api.accounts.app;

import com.zeitwide.api.accounts.domain.AccountIdentity;
import com.zeitwide.api.accounts.domain.SignupSource;
import java.util.Optional;
import org.springframework.lang.Nullable;

/** Persistence boundary for sign-in-subject account provisioning. */
public interface AccountRepository {

  void lockSubject(String subject);

  Optional<AccountIdentity> findBySubject(String subject);

  AccountIdentity createForSubject(
      String subject, String accountName, @Nullable SignupSource signupSource);
}
