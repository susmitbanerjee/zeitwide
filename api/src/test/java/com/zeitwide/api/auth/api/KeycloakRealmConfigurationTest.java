package com.zeitwide.api.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class KeycloakRealmConfigurationTest {

  @Test
  void realmDefinesProvidersPkceApiAudienceAndShortAccessTokens() throws Exception {
    JsonNode realm = new ObjectMapper().readTree(Path.of("../deploy/keycloak/realm.json").toFile());

    assertThat(realm.path("realm").asText()).isEqualTo("zeitwide");
    assertThat(realm.path("accessTokenLifespan").asInt()).isEqualTo(300);
    assertThat(client(realm, "zeitwide-web").path("publicClient").asBoolean()).isTrue();
    assertThat(
            client(realm, "zeitwide-web")
                .path("attributes")
                .path("pkce.code.challenge.method")
                .asText())
        .isEqualTo("S256");
    assertThat(client(realm, "zeitwide-api").path("bearerOnly").asBoolean()).isTrue();
    Set<String> providers =
        StreamSupport.stream(realm.path("identityProviders").spliterator(), false)
            .map(provider -> provider.path("alias").asText())
            .collect(java.util.stream.Collectors.toSet());
    assertThat(providers).containsExactlyInAnyOrder("google", "microsoft", "github");
    assertThat(realm.toString())
        .contains(
            "${GOOGLE_CLIENT_ID}",
            "${GOOGLE_CLIENT_SECRET}",
            "${GITHUB_CLIENT_ID}",
            "${GITHUB_CLIENT_SECRET}");
    assertThat(
            realm
                .path("clientScopes")
                .get(0)
                .path("protocolMappers")
                .get(0)
                .path("config")
                .path("included.client.audience")
                .asText())
        .isEqualTo("zeitwide-api");
  }

  private static JsonNode client(JsonNode realm, String clientId) {
    return StreamSupport.stream(realm.path("clients").spliterator(), false)
        .filter(client -> client.path("clientId").asText().equals(clientId))
        .findFirst()
        .orElseThrow();
  }
}
