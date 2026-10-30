package com.zeitwide.api.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakTokenValidatorTest {

  @Test
  void requiresTheConfiguredIssuerAndApiAudience() {
    Instant now = Instant.now();
    Jwt valid =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("https://auth.example.test/realms/zeitwide")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .audience(List.of("zeitwide-api"))
            .build();
    Jwt wrongIssuer =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("https://attacker.example/realms/zeitwide")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .audience(List.of("zeitwide-api"))
            .build();
    Jwt wrongAudience =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("https://auth.example.test/realms/zeitwide")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .audience(List.of("other-api"))
            .build();
    var validator =
        SecurityConfiguration.keycloakTokenValidator(
            "https://auth.example.test/realms/zeitwide", "zeitwide-api");

    assertThat(validator.validate(valid).hasErrors()).isFalse();
    assertThat(validator.validate(wrongIssuer).hasErrors()).isTrue();
    assertThat(validator.validate(wrongAudience).hasErrors()).isTrue();
  }
}
