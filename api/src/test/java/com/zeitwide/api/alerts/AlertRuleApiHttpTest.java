package com.zeitwide.api.alerts;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.auth.api.AccountAuthenticationToken;
import com.zeitwide.api.auth.domain.AccountPrincipal;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class AlertRuleApiHttpTest {

  private static final String RULE =
      """
      {"name":"Short-notice GBP","calendars":["GB-CHAPS","GB-EAW"],
       "events":["closure_added","closure_removed"],"max_notice_days":14}
      """;

  @Autowired private JdbcClient jdbc;
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  private UUID accountId;
  private UUID otherAccountId;

  @BeforeEach
  void createAccounts() {
    accountId = account("Alert rule test");
    otherAccountId = account("Someone else");
  }

  @Test
  void shouldCreateListReplaceAndDeleteAnAccountsRules() throws Exception {
    String created =
        mvc.perform(create(RULE, "alert-rule-test-key-0001").with(as(accountId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Short-notice GBP"))
            .andExpect(jsonPath("$.calendars[1]").value("GB-EAW"))
            .andExpect(jsonPath("$.max_notice_days").value(14))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(created).path("id").asText();

    mvc.perform(get("/v1/account/alert-rules").with(as(accountId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(id));

    mvc.perform(
            put("/v1/account/alert-rules/{id}", id)
                .with(as(accountId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Every USD change","calendars":["US-FEDWIRE"],
                     "events":["closure_added"],"max_notice_days":null}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Every USD change"))
        .andExpect(jsonPath("$.max_notice_days").isEmpty());

    mvc.perform(delete("/v1/account/alert-rules/{id}", id).with(as(accountId)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/v1/account/alert-rules").with(as(accountId)))
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void shouldKeepRulesInsideTheirAccount() throws Exception {
    String created =
        mvc.perform(create(RULE, "alert-rule-test-key-0002").with(as(accountId)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(created).path("id").asText();

    mvc.perform(get("/v1/account/alert-rules").with(as(otherAccountId)))
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(
            put("/v1/account/alert-rules/{id}", id)
                .with(as(otherAccountId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(RULE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
    mvc.perform(delete("/v1/account/alert-rules/{id}", id).with(as(otherAccountId)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/v1/account/alert-rules").with(as(accountId)))
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void shouldRejectAReusedIdempotencyKey() throws Exception {
    mvc.perform(create(RULE, "alert-rule-test-key-0003").with(as(accountId)))
        .andExpect(status().isCreated());
    mvc.perform(create(RULE, "alert-rule-test-key-0003").with(as(accountId)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
  }

  @Test
  void shouldRejectMalformedRules() throws Exception {
    mvc.perform(
            create(
                    """
                    {"name":"x","calendars":["gb-chaps"],"events":["closure_added"]}
                    """,
                    "alert-rule-test-key-0004")
                .with(as(accountId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
    mvc.perform(create(RULE, "short").with(as(accountId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void shouldRequireAnIdentity() throws Exception {
    mvc.perform(get("/v1/account/alert-rules"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
  }

  private UUID account(String name) {
    return jdbc.sql("INSERT INTO account (name) VALUES (:name) RETURNING id")
        .param("name", name)
        .query(UUID.class)
        .single();
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
      String body, String idempotencyKey) {
    return post("/v1/account/alert-rules")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static RequestPostProcessor as(UUID account) {
    return authentication(
        new AccountAuthenticationToken(new AccountPrincipal(account, null, null), List.of()));
  }
}
