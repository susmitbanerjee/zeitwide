package com.zeitwide.api.auth.api;

import com.zeitwide.api.auth.domain.AccountPrincipal;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/** A validated account identity. Credentials are deliberately not retained. */
@SuppressWarnings("serial")
public final class AccountAuthenticationToken extends AbstractAuthenticationToken {

  private final AccountPrincipal principal;

  public AccountAuthenticationToken(
      AccountPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public AccountPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public Object getCredentials() {
    return null;
  }
}
