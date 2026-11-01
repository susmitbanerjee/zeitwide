package com.zeitwide.api.auth.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the API trusts the web app. After signing someone in with Google, Microsoft or GitHub, the
 * web app signs a short-lived token for each call it makes on their behalf, and the API accepts
 * only tokens signed with the secret both of them hold.
 *
 * @param issuer the {@code iss} the web app puts in its tokens: its public origin
 * @param audience the {@code aud} the tokens must name
 * @param tokenSecret the HMAC-SHA256 secret, at least 32 characters; there is deliberately no
 *     default, because a known default would let anyone mint tokens
 */
@Validated
@ConfigurationProperties("zeitwide.auth")
public record AuthProperties(
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotBlank @Size(min = 32) String tokenSecret) {

  /** Never prints the secret: records print every component by default. */
  @Override
  public String toString() {
    return "AuthProperties[issuer="
        + issuer
        + ", audience="
        + audience
        + ", tokenSecret=<redacted>]";
  }
}
