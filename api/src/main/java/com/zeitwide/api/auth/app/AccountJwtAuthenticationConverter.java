package com.zeitwide.api.auth.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zeitwide.api.accounts.app.AccountProvisioner;
import com.zeitwide.api.accounts.domain.AccountIdentity;
import com.zeitwide.api.auth.domain.AccountPrincipal;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Reads the roles the web app put in its token and provisions the matching local account. */
@Component
public class AccountJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  /** The web app's claim for the AI referral it saw at sign-in, such as {@code ai:openai}. */
  static final String SIGNUP_SOURCE_CLAIM = "signup_source";

  private static final Logger log =
      LoggerFactory.getLogger(AccountJwtAuthenticationConverter.class);
  private final AccountProvisioner accounts;
  private final Cache<String, Boolean> observedTokens =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(10)).build();

  AccountJwtAuthenticationConverter(AccountProvisioner accounts) {
    this.accounts = accounts;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String name = firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("email"));
    AccountIdentity identity =
        accounts.resolve(jwt.getSubject(), name, jwt.getClaimAsString(SIGNUP_SOURCE_CLAIM));
    AccountPrincipal principal =
        new AccountPrincipal(identity.accountId(), identity.appUserId(), null);
    String fingerprint = HexFormat.of().formatHex(ApiKeyToken.sha256(jwt.getTokenValue()));
    if (observedTokens.asMap().putIfAbsent(fingerprint, Boolean.TRUE) == null) {
      log.atInfo()
          .addKeyValue("event", "login")
          .addKeyValue("account_id", principal.accountId())
          .addKeyValue("app_user_id", principal.appUserId())
          .log("Login authenticated");
    }
    Collection<GrantedAuthority> authorities = new ArrayList<>();
    if (jwt.getClaim("roles") instanceof Collection<?> roles) {
      roles.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
          .forEach(authorities::add);
    }
    return new AccountJwtToken(jwt, principal, authorities);
  }

  private static String firstNonBlank(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return "Zeitwide account";
  }

  @SuppressWarnings("serial")
  private static final class AccountJwtToken extends JwtAuthenticationToken {
    private final AccountPrincipal principal;

    private AccountJwtToken(
        Jwt jwt, AccountPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
      super(jwt, authorities, principal.accountId().toString());
      this.principal = principal;
    }

    @Override
    public Object getPrincipal() {
      return principal;
    }
  }
}
