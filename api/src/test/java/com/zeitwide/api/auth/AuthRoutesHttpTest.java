package com.zeitwide.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.auth.api.AccountAuthenticationToken;
import com.zeitwide.api.auth.app.AccountJwtAuthenticationConverter;
import com.zeitwide.api.auth.app.ApiKeyAuthenticator;
import com.zeitwide.api.auth.domain.AccountPrincipal;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = "zeitwide.rate-limits.anonymous.capacity=1000")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, AuthRoutesHttpTest.ProbeConfiguration.class})
class AuthRoutesHttpTest {

  private static final String ANSWER_PROBE = "/v1/calendars/security-probe";
  private static final String ACCOUNT_PROBE = "/v1/account/security-probe";
  private static final String ADMIN_PROBE = "/admin/security-probe";
  private static final String INTERNAL_PROBE = "/internal/security-probe";
  private static final String DOCS_PROBE = "/docs/security-probe";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private ApiKeyAuthenticator apiKeys;
  @Autowired private AccountJwtAuthenticationConverter jwtConverter;

  private UUID accountId;
  private UUID userId;

  @BeforeEach
  void createAccount() {
    accountId =
        jdbc.sql("INSERT INTO account (name) VALUES ('Auth test') RETURNING id")
            .query(UUID.class)
            .single();
    userId =
        jdbc.sql(
                "INSERT INTO app_user (account_id, subject, role) "
                    + "VALUES (:account, :subject, 'owner') RETURNING id")
            .param("account", accountId)
            .param("subject", "auth-test-" + UUID.randomUUID())
            .query(UUID.class)
            .single();
  }

  @Test
  void answersNeedAnIdentityAndAreRateLimitedAndDocsArePublic() throws Exception {
    mvc.perform(get(ANSWER_PROBE))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
    mvc.perform(get(ANSWER_PROBE).with(accountAuthentication()))
        .andExpect(status().isOk())
        .andExpect(header().exists("RateLimit-Limit"));
    mvc.perform(get(DOCS_PROBE)).andExpect(status().isOk());
  }

  @Test
  void responsesTellBrowsersToUseHttpsAndNeverLoadAnything() throws Exception {
    mvc.perform(get(ANSWER_PROBE))
        .andExpect(
            header().string("Strict-Transport-Security", "max-age=63072000 ; includeSubDomains"))
        .andExpect(
            header()
                .string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
  }

  @Test
  void anAddressThatKeepsSendingInvalidKeysIsTurnedAwayBeforeTheDatabaseIsAsked() throws Exception {
    String unknownKey = "Bearer zw_live_AbCd1234_" + "B".repeat(43);
    for (int attempt = 0; attempt < 30; attempt++) {
      mvc.perform(get(ANSWER_PROBE).header("Authorization", unknownKey).with(from("203.0.113.50")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("invalid_api_key"));
    }
    mvc.perform(get(ANSWER_PROBE).header("Authorization", unknownKey).with(from("203.0.113.50")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    mvc.perform(get(ANSWER_PROBE).header("Authorization", unknownKey).with(from("203.0.113.51")))
        .andExpect(status().isUnauthorized());
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor from(
      String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  @Test
  void mcpChallengesAnonymousClientsAndRefusesUnknownKeys() throws Exception {
    mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"zeitwide\""));

    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer zw_live_AbCd1234_" + "A".repeat(43))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"zeitwide\""));
  }

  @Test
  void theWebAppsSignedTokensAreAcceptedAndNothingElseIs() throws Exception {
    mvc.perform(
            get(ACCOUNT_PROBE)
                .header(
                    "Authorization",
                    "Bearer " + webToken(TOKEN_SECRET, 60, "zeitwide-api", List.of())))
        .andExpect(status().isOk());
    // Signed with another secret, for another audience, or meant to last for hours: all refused.
    mvc.perform(
            get(ACCOUNT_PROBE)
                .header(
                    "Authorization",
                    "Bearer "
                        + webToken(
                            "some-other-secret-0123456789abcdef0123456789",
                            60,
                            "zeitwide-api",
                            List.of())))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get(ACCOUNT_PROBE)
                .header(
                    "Authorization",
                    "Bearer " + webToken(TOKEN_SECRET, 60, "other-api", List.of())))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get(ACCOUNT_PROBE)
                .header(
                    "Authorization",
                    "Bearer " + webToken(TOKEN_SECRET, 4 * 3600, "zeitwide-api", List.of())))
        .andExpect(status().isUnauthorized());
    // An unsigned token with the right claims is not a token.
    mvc.perform(get(ACCOUNT_PROBE).header("Authorization", "Bearer " + unsignedToken()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void onlyATokenCarryingTheReviewerRoleReachesTheAdminRoutes() throws Exception {
    mvc.perform(
            get(ADMIN_PROBE)
                .header(
                    "Authorization",
                    "Bearer " + webToken(TOKEN_SECRET, 60, "zeitwide-api", List.of())))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(ADMIN_PROBE)
                .header(
                    "Authorization",
                    "Bearer " + webToken(TOKEN_SECRET, 60, "zeitwide-api", List.of("reviewer"))))
        .andExpect(status().isOk());
  }

  private static final String TOKEN_SECRET = "test-only-secret-0123456789abcdef0123456789";

  /** A token as the web app signs it: HS256, its issuer, a subject, and the roles it grants. */
  private static String webToken(
      String secret, long lifetimeSeconds, String audience, List<String> roles) throws Exception {
    Instant now = Instant.now();
    var claims =
        new com.nimbusds.jwt.JWTClaimsSet.Builder()
            .issuer("http://localhost:3000")
            .audience(audience)
            .subject("google:" + UUID.randomUUID())
            .claim("name", "Token Tester")
            .claim("roles", roles)
            .issueTime(java.util.Date.from(now))
            .expirationTime(java.util.Date.from(now.plusSeconds(lifetimeSeconds)))
            .build();
    var jwt =
        new com.nimbusds.jwt.SignedJWT(
            new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
    jwt.sign(
        new com.nimbusds.jose.crypto.MACSigner(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private static String unsignedToken() {
    var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
    long exp = Instant.now().plusSeconds(60).getEpochSecond();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String body =
        encoder.encodeToString(
            ("{\"iss\":\"http://localhost:3000\",\"aud\":\"zeitwide-api\",\"sub\":\"x\",\"iat\":"
                    + (exp - 60)
                    + ",\"exp\":"
                    + exp
                    + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return header + "." + body + ".";
  }

  @Test
  void mcpAcceptsASignedInUsersToken() throws Exception {
    mvc.perform(
            post("/mcp")
                .with(jwt())
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"oauth-test\","
                        + "\"version\":\"1.0\"}}}"))
        .andExpect(status().isOk());
  }

  @Test
  void accountAndAdminRoutesRejectAnonymousAndWrongRoles() throws Exception {
    mvc.perform(get(ACCOUNT_PROBE)).andExpect(status().isUnauthorized());
    mvc.perform(get(ACCOUNT_PROBE).with(accountAuthentication())).andExpect(status().isOk());

    mvc.perform(get(ADMIN_PROBE)).andExpect(status().isUnauthorized());
    mvc.perform(get(ADMIN_PROBE).with(accountAuthentication())).andExpect(status().isForbidden());
    mvc.perform(get(ADMIN_PROBE).with(reviewerAuthentication())).andExpect(status().isOk());
  }

  @Test
  void internalRoutesRejectUserAndApiKeyCallers() throws Exception {
    mvc.perform(get(INTERNAL_PROBE)).andExpect(status().isUnauthorized());
    mvc.perform(get(INTERNAL_PROBE).with(accountAuthentication()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get(INTERNAL_PROBE).with(reviewerAuthentication()))
        .andExpect(status().isUnauthorized());

    String created =
        mvc.perform(
                post("/v1/account/api-keys")
                    .with(accountAuthentication())
                    .header("Idempotency-Key", "internal-route-test-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"internal test\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String key = json.readTree(created).path("key").asText();
    mvc.perform(get(INTERNAL_PROBE).header("Authorization", "Bearer " + key))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createsListsAndRevokesHashedKeysAndRevocationIsImmediate() throws Exception {
    String created =
        mvc.perform(
                post("/v1/account/api-keys")
                    .with(accountAuthentication())
                    .header("Idempotency-Key", "api-key-create-test-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"automation\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode createdJson = json.readTree(created);
    String key = createdJson.path("key").asText();
    String prefix = createdJson.path("prefix").asText();
    UUID keyId = UUID.fromString(createdJson.path("id").asText());
    String secret = key.substring(key.lastIndexOf('_') + 1);

    assertThat(key).matches("zw_live_[A-Za-z0-9]{8}_[A-Za-z0-9]{43}");
    assertThat(prefix).isEqualTo(key.substring(0, key.lastIndexOf('_')));
    mvc.perform(
            post("/v1/account/api-keys")
                .with(accountAuthentication())
                .header("Idempotency-Key", "api-key-create-test-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"automation\"}"))
        .andExpect(status().isConflict());
    assertThat(
            jdbc.sql("SELECT count(*) FROM api_key WHERE account_id = :account")
                .param("account", accountId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    byte[] storedHash =
        jdbc.sql("SELECT secret_sha256 FROM api_key WHERE id = :id")
            .param("id", keyId)
            .query(byte[].class)
            .single();
    assertThat(storedHash).containsExactly(ApiKeyToken.sha256(secret));
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'api_key' "
                        + "AND column_name IN ('secret', 'key_value', 'plaintext')")
                .query(Long.class)
                .single())
        .isZero();

    String listed =
        mvc.perform(get("/v1/account/api-keys").header("Authorization", "Bearer " + key))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(listed).contains(prefix).doesNotContain(secret);
    assertThat(json.readTree(listed).get(0).has("key")).isFalse();
    assertThat(apiKeys.authenticate(key)).isPresent();
    assertThat(
            jdbc.sql("SELECT last_used_at IS NOT NULL FROM api_key WHERE id = :id")
                .param("id", keyId)
                .query(Boolean.class)
                .single())
        .isTrue();

    mvc.perform(delete("/v1/account/api-keys/" + keyId).with(accountAuthentication()))
        .andExpect(status().isNoContent());
    assertThat(apiKeys.authenticate(key)).isEmpty();
    mvc.perform(get(ANSWER_PROBE).header("Authorization", "Bearer " + key))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void firstSignInCreatesOneFreeAccountAndAppUser() {
    String subject = "new-user-" + UUID.randomUUID();
    Instant now = Instant.now();
    Jwt jwt =
        Jwt.withTokenValue("header.payload.signature")
            .header("alg", "RS256")
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .audience(List.of("zeitwide-api"))
            .claim("name", "New User")
            .claim("roles", List.of("reviewer"))
            .build();

    var first = jwtConverter.convert(jwt);
    var second = jwtConverter.convert(jwt);

    assertThat(first.getPrincipal()).isInstanceOf(AccountPrincipal.class);
    AccountPrincipal firstPrincipal = (AccountPrincipal) first.getPrincipal();
    AccountPrincipal secondPrincipal = (AccountPrincipal) second.getPrincipal();
    assertThat(secondPrincipal.accountId()).isEqualTo(firstPrincipal.accountId());
    assertThat(first.getAuthorities())
        .extracting(authority -> authority.getAuthority())
        .contains("ROLE_reviewer");
    assertThat(
            jdbc.sql("SELECT plan::text FROM account WHERE id = :id")
                .param("id", firstPrincipal.accountId())
                .query(String.class)
                .single())
        .isEqualTo("free");
    assertThat(
            jdbc.sql("SELECT count(*) FROM app_user WHERE subject = :subject")
                .param("subject", subject)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void firstSignInStoresTheAiReferralTheWebAppSaw() {
    String subject = "referred-" + UUID.randomUUID();

    AccountPrincipal principal = signIn(subject, "ai:anthropic");

    assertThat(signupSource(principal)).isEqualTo("ai:anthropic");
  }

  @Test
  void laterSignInsNeverChangeTheSignupSource() {
    String subject = "referred-twice-" + UUID.randomUUID();

    AccountPrincipal first = signIn(subject, "ai:perplexity");
    AccountPrincipal second = signIn(subject, "ai:openai");

    assertThat(second.accountId()).isEqualTo(first.accountId());
    assertThat(signupSource(first)).isEqualTo("ai:perplexity");
  }

  @Test
  void firstSignInWithoutAReferralStoresNoSignupSource() {
    assertThat(signupSource(signIn("unreferred-" + UUID.randomUUID(), null))).isNull();
  }

  @Test
  void firstSignInIgnoresAForgedSignupSource() {
    for (Object forged :
        List.of("ai:evil", "ai:OpenAI", "ai:openai ", "openai", "x'); DROP TABLE account;--", 7)) {
      AccountPrincipal principal = signIn("forged-" + UUID.randomUUID(), forged);

      assertThat(signupSource(principal)).as("signup source from %s", forged).isNull();
    }
  }

  private AccountPrincipal signIn(String subject, Object signupSource) {
    Instant now = Instant.now();
    Jwt.Builder jwt =
        Jwt.withTokenValue("header." + subject + ".signature")
            .header("alg", "HS256")
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .audience(List.of("zeitwide-api"))
            .claim("name", "Referred User");
    if (signupSource != null) {
      jwt.claim("signup_source", signupSource);
    }
    return (AccountPrincipal) jwtConverter.convert(jwt.build()).getPrincipal();
  }

  private String signupSource(AccountPrincipal principal) {
    return jdbc.sql("SELECT signup_source FROM account WHERE id = :id")
        .param("id", principal.accountId())
        .query((rs, row) -> java.util.Optional.ofNullable(rs.getString("signup_source")))
        .single()
        .orElse(null);
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor
      accountAuthentication() {
    return authentication(
        new AccountAuthenticationToken(new AccountPrincipal(accountId, userId, null), List.of()));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor
      reviewerAuthentication() {
    return authentication(
        new TestingAuthenticationToken(
            "reviewer", null, List.of(new SimpleGrantedAuthority("ROLE_reviewer"))));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {
    @Bean
    ProbeController probeController() {
      return new ProbeController();
    }
  }

  @RestController
  static class ProbeController {
    @GetMapping(ANSWER_PROBE)
    String answers() {
      return "answers";
    }

    @GetMapping(ACCOUNT_PROBE)
    String account() {
      return "account";
    }

    @GetMapping(ADMIN_PROBE)
    String admin() {
      return "admin";
    }

    @GetMapping(INTERNAL_PROBE)
    String internal() {
      return "internal";
    }

    @GetMapping(DOCS_PROBE)
    String docs() {
      return "docs";
    }
  }
}
