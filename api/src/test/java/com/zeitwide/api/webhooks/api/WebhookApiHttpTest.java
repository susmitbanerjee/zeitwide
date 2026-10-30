package com.zeitwide.api.webhooks.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.auth.api.AccountAuthenticationToken;
import com.zeitwide.api.auth.domain.AccountPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class WebhookApiHttpTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  private UUID accountId;

  @BeforeEach
  void createAccount() {
    accountId =
        jdbc.sql("INSERT INTO account (name) VALUES ('Webhook test') RETURNING id")
            .query(UUID.class)
            .single();
  }

  @Test
  void createsListsUpdatesAndDeletesWebhooksAndStoresOnlyEncryptedSecrets() throws Exception {
    String response =
        mvc.perform(
                post("/v1/account/webhooks")
                    .with(accountAuthentication())
                    .header("Idempotency-Key", "webhook-create-test-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"url":"https://hooks.example.com/old","events":["closure_added"]}
                        """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode created = json.readTree(response);
    UUID id = UUID.fromString(created.path("id").asText());
    String secret = created.path("secret").asText();
    assertThat(secret).startsWith("whsec_");
    assertThat(created.path("events").get(0).asText()).isEqualTo("closure_added");

    byte[] storedCiphertext =
        jdbc.sql("SELECT signing_secret_ciphertext FROM webhook_subscription WHERE id = :id")
            .param("id", id)
            .query(byte[].class)
            .single();
    assertThat(new String(storedCiphertext, StandardCharsets.UTF_8)).doesNotContain(secret);

    mvc.perform(
            post("/v1/account/webhooks")
                .with(accountAuthentication())
                .header("Idempotency-Key", "webhook-create-test-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"url\":\"https://hooks.example.com/old\",\"events\":[\"closure_added\"]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_key_reused"));

    mvc.perform(get("/v1/account/webhooks").with(accountAuthentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].secret").doesNotExist());

    mvc.perform(
            put("/v1/account/webhooks/{id}", id)
                .with(accountAuthentication())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"url":"https://hooks.example.com/new","events":["closure_removed"]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("https://hooks.example.com/new"))
        .andExpect(jsonPath("$.events[0]").value("closure_removed"));

    mvc.perform(delete("/v1/account/webhooks/{id}", id).with(accountAuthentication()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/v1/account/webhooks").with(accountAuthentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor
      accountAuthentication() {
    return authentication(
        new AccountAuthenticationToken(new AccountPrincipal(accountId, null, null), List.of()));
  }
}
