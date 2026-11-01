package com.zeitwide.api.answers.api;

import static com.zeitwide.api.answers.GoldenDatabase.GOLDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeitwide.api.accounts.app.AccountProvisioner;
import com.zeitwide.api.alerts.app.AlertRuleService;
import com.zeitwide.api.answers.app.DatasetSource;
import com.zeitwide.api.answers.domain.ReleaseStamp;
import com.zeitwide.api.auth.app.AccountJwtAuthenticationConverter;
import com.zeitwide.api.auth.app.ApiKeyRepository;
import com.zeitwide.api.auth.app.ApiKeyService;
import com.zeitwide.api.auth.app.AuthProperties;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import com.zeitwide.api.auth.domain.StoredApiKey;
import com.zeitwide.api.changes.app.ChangeRepository;
import com.zeitwide.api.operations.app.OperationsService;
import com.zeitwide.api.ratelimits.app.RateLimiter;
import com.zeitwide.api.ratelimits.domain.Allowance;
import com.zeitwide.api.ratelimits.domain.Limit;
import com.zeitwide.api.receipts.ReceiptTestConfiguration;
import com.zeitwide.api.review.app.ReviewService;
import com.zeitwide.api.webhooks.app.OutboxDrainService;
import com.zeitwide.api.webhooks.app.WebhookSubscriptionService;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Every endpoint and every error code, through MockMvc with the real use cases, engine, filter and
 * advice. Only the ports are stubbed: the golden dataset stands in for Postgres.
 */
@WebMvcTest
@Import({AnswersApiTest.UseCases.class, ReceiptTestConfiguration.class})
@TestPropertySource(
    properties = {
      "zeitwide.answers.release-check-interval=PT0S",
      "zeitwide.auth.audience=zeitwide-api"
    })
class AnswersApiTest {

  private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
  // A made-up test key, never a real credential.
  private static final String KEY_PREFIX = "zw_live_AbCd1234"; // gitleaks:allow
  private static final String KEY_SECRET = "s3cr3t".repeat(6) + "1234567";
  private static final String KEY = KEY_PREFIX + "_" + KEY_SECRET;

  @TestConfiguration(proxyBeanMethods = false)
  @ComponentScan(
      value = {
        "com.zeitwide.api.answers.app",
        "com.zeitwide.api.auth.app",
        "com.zeitwide.api.changes.app",
        "com.zeitwide.api.ratelimits.app",
        "com.zeitwide.api.receipts",
        "com.zeitwide.api.web"
      },
      excludeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {
                AccountJwtAuthenticationConverter.class,
                AccountProvisioner.class,
                ApiKeyService.class
              }))
  static class UseCases {
    @Bean
    AuthProperties mcpAuthProperties() {
      return new AuthProperties(
          "http://localhost:3000", "zeitwide-api", "test-only-secret-0123456789abcdef0123456789");
    }

    @Bean
    SecurityFilterChain answersTestSecurity(HttpSecurity http) throws Exception {
      return http.csrf(AbstractHttpConfigurer::disable)
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .build();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private RequestMappingHandlerMapping mappings;
  @MockitoBean private DatasetSource datasets;
  @MockitoBean private RateLimiter rateLimiter;
  @MockitoBean private ApiKeyRepository apiKeys;
  @MockitoBean private ChangeRepository changes;
  @MockitoBean private ApiKeyService apiKeyService;
  @MockitoBean private AccountJwtAuthenticationConverter accountJwtAuthenticationConverter;
  @MockitoBean private WebhookSubscriptionService webhookSubscriptionService;
  @MockitoBean private OutboxDrainService outboxDrainService;
  @MockitoBean private AlertRuleService alertRuleService;
  @MockitoBean private ReviewService reviewService;
  @MockitoBean private OperationsService operationsService;

  @BeforeEach
  void stubPorts() {
    when(datasets.latestRelease())
        .thenReturn(Optional.of(new ReleaseStamp(GOLDEN.release(), GOLDEN.asOf())));
    when(datasets.load(any())).thenReturn(GOLDEN);
    when(apiKeys.recordUse(any())).thenReturn(true);
    when(rateLimiter.tryConsume(anyLong(), any())).thenReturn(new Allowance.Allowed(60, 59));
  }

  @Test
  void shouldListEveryCalendarWithItsCoverage() throws Exception {
    mvc.perform(get("/v1/calendars"))
        .andExpect(status().isOk())
        .andExpect(header().string(ZeitwideHeaders.DATASET_RELEASE, "2026.10.29.0"))
        .andExpect(header().string(ZeitwideHeaders.AS_OF, "2026-10-29T00:00:00Z"))
        .andExpect(jsonPath("$.dataset_release").value("2026.10.29.0"))
        .andExpect(jsonPath("$.calendars", hasSize(8)))
        .andExpect(jsonPath("$.calendars[?(@.id == 'US-FEDNOW')].coverage.type").value("unlimited"))
        .andExpect(jsonPath("$.calendars[?(@.id == 'GB-CHAPS')].coverage.from").value("2022-01-01"))
        .andExpect(jsonPath("$.calendars[?(@.id == 'GB-CHAPS')].coverage.to").value("2028-12-31"))
        .andExpect(
            jsonPath("$.calendars[?(@.id == 'US-FEDWIRE')].weekend[*]")
                .value(contains("saturday", "sunday")));
  }

  @Test
  void shouldExplainAClosedDayWithItsEvidence() throws Exception {
    mvc.perform(get("/v1/calendars/US-FEDWIRE/days/2026-11-26"))
        .andExpect(status().isOk())
        .andExpect(header().string(ZeitwideHeaders.CALENDAR_REVISION, "US-FEDWIRE=1"))
        .andExpect(header().string(ZeitwideHeaders.DATASET_RELEASE, "2026.10.29.0"))
        .andExpect(jsonPath("$.is_business_day").value(false))
        .andExpect(jsonPath("$.calendar_revisions.US-FEDWIRE").value(1))
        .andExpect(jsonPath("$.calendars[0].decided_by").value("entry"))
        .andExpect(jsonPath("$.calendars[0].reason").value("Thanksgiving Day"))
        .andExpect(jsonPath("$.calendars[0].basis").value("published"))
        .andExpect(jsonPath("$.calendars[0].sources[*].publisher").isNotEmpty())
        .andExpect(jsonPath("$.calendars[0].sources[0].snapshot_sha256").isString())
        .andExpect(jsonPath("$.calendars[0].sources[0].retrieved_at").isString())
        .andExpect(jsonPath("$.calendars[0].sources[0].url").value(startsWith("https://")));
  }

  @Test
  void shouldAnswerEveryDayInARange() throws Exception {
    mvc.perform(
            get("/v1/calendars/GB-CHAPS/days")
                .param("from", "2022-09-17")
                .param("to", "2022-09-20"))
        .andExpect(status().isOk())
        .andExpect(header().string(ZeitwideHeaders.DATASET_RELEASE, "2026.10.29.0"))
        .andExpect(jsonPath("$.from").value("2022-09-17"))
        .andExpect(jsonPath("$.today").isString())
        .andExpect(jsonPath("$.days", hasSize(4)))
        .andExpect(jsonPath("$.days[0].decided_by").value("weekend_rule"))
        .andExpect(jsonPath("$.days[2].date").value("2022-09-19"))
        .andExpect(jsonPath("$.days[2].is_open").value(false))
        .andExpect(jsonPath("$.days[2].decided_by").value("entry"))
        .andExpect(jsonPath("$.days[2].sources[0].snapshot_sha256").isString())
        .andExpect(jsonPath("$.days[2].next_business_day").value("2022-09-20"))
        .andExpect(jsonPath("$.days[2].previous_business_day").value("2022-09-16"))
        .andExpect(jsonPath("$.days[0].sources").isEmpty())
        .andExpect(jsonPath("$.days[3].is_open").value(true))
        .andExpect(jsonPath("$.receipt.id").isString());
  }

  @Test
  void shouldLeaveOutDaysBeyondCoverageAndDefaultTheWindow() throws Exception {
    mvc.perform(
            get("/v1/calendars/GB-CHAPS/days")
                .param("from", "2028-12-30")
                .param("to", "2029-01-02"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days", hasSize(2)));
    mvc.perform(get("/v1/calendars/US-FEDNOW/days"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.from").value(org.hamcrest.Matchers.endsWith("-01-01")))
        .andExpect(
            jsonPath(
                "$.days", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(90))));
    problem(
        mvc.perform(
            get("/v1/calendars/GB-CHAPS/days")
                .param("from", "2022-01-01")
                .param("to", "2024-01-01")),
        400,
        "validation_failed");
    problem(mvc.perform(get("/v1/calendars/XX-NONE/days")), 404, "unknown_calendar");
  }

  @Test
  void shouldSayWhenTheWeekendRuleDecided() throws Exception {
    mvc.perform(get("/v1/calendars/US-FEDWIRE/days/2026-11-28"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_business_day").value(false))
        .andExpect(jsonPath("$.calendars[0].decided_by").value("weekend_rule"))
        .andExpect(jsonPath("$.calendars[0].reason").doesNotExist())
        .andExpect(jsonPath("$.calendars[0].sources").isEmpty());
  }

  @Test
  void shouldVerifyAnswerReceiptAndRejectTampering() throws Exception {
    String response =
        mvc.perform(get("/v1/calendars/US-FEDWIRE/days/2026-11-26"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var receipt = json.readTree(response).path("receipt");
    assertThat(receipt.path("payload").path("answer").has("receipt")).isFalse();

    mvc.perform(
            post("/v1/receipts/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(receipt.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true));

    ObjectNode tampered = (ObjectNode) receipt.deepCopy();
    ((ObjectNode) tampered.path("payload").path("answer")).put("is_business_day", true);
    mvc.perform(
            post("/v1/receipts/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tampered.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false));
  }

  @Test
  void shouldAnswerAsOfAPastInstant() throws Exception {
    String funeral = "/v1/calendars/GB-CHAPS/days/2022-09-19";

    mvc.perform(get(funeral).param("as_of", "2022-09-09T12:00:00Z"))
        .andExpect(jsonPath("$.is_business_day").value(true))
        .andExpect(jsonPath("$.as_of").value("2022-09-09T12:00:00Z"))
        .andExpect(header().string(ZeitwideHeaders.CALENDAR_REVISION, "GB-CHAPS=1"));
    mvc.perform(get(funeral).param("as_of", "2022-09-11T12:00:00+00:00"))
        .andExpect(jsonPath("$.is_business_day").value(false))
        .andExpect(header().string(ZeitwideHeaders.CALENDAR_REVISION, "GB-CHAPS=2"));
    mvc.perform(get(funeral).param("as_of", "2022-09-10T00:00:00Z"))
        .andExpect(jsonPath("$.is_business_day").value(true))
        .andExpect(header().string(ZeitwideHeaders.CALENDAR_REVISION, "GB-CHAPS=1"));
  }

  @Test
  void shouldApplyQueryAsOfToEveryPostAnswerEndpoint() throws Exception {
    String asOf = "2022-09-09T12:00:00Z";
    mvc.perform(
            post("/v1/business-days/check")
                .param("as_of", asOf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"calendars\":[\"GB-CHAPS\"],\"date\":\"2022-09-19\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.as_of").value(asOf));
    mvc.perform(
            post("/v1/business-days/add")
                .param("as_of", asOf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"calendars\":[\"GB-CHAPS\"],\"date\":\"2022-09-19\",\"days\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.as_of").value(asOf));
    mvc.perform(
            post("/v1/business-days/next")
                .param("as_of", asOf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"calendars\":[\"GB-CHAPS\"],\"date\":\"2022-09-19\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.as_of").value(asOf));
  }

  @Test
  void shouldAnswerALaterAsOfAsOfTheRelease() throws Exception {
    mvc.perform(
            get("/v1/calendars/US-FEDWIRE/days/2026-11-26").param("as_of", "2030-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.as_of").value("2026-10-29T00:00:00Z"));
  }

  @Test
  void shouldListClosuresInARange() throws Exception {
    mvc.perform(
            get("/v1/calendars/US-FEDWIRE/closures")
                .param("from", "2026-01-01")
                .param("to", "2026-12-31"))
        .andExpect(status().isOk())
        .andExpect(header().string(ZeitwideHeaders.CALENDAR_REVISION, "US-FEDWIRE=1"))
        .andExpect(jsonPath("$.calendar_revision").value(1))
        .andExpect(jsonPath("$.closures", hasSize(11)))
        .andExpect(jsonPath("$.closures[0].date").value("2026-01-01"))
        .andExpect(jsonPath("$.closures[9].reason").value("Thanksgiving Day"))
        .andExpect(jsonPath("$.closures[9].sources[0].publisher").isString());
  }

  @Test
  void shouldCombineCalendarsWhenChecking() throws Exception {
    postJson(
            """
            {"calendars": ["US-FEDWIRE", "EU-T2"], "date": "2026-04-03", "combine": "any_open"}
            """,
            "/v1/business-days/check")
        .andExpect(status().isOk())
        .andExpect(header().string(ZeitwideHeaders.CALENDAR_REVISION, "EU-T2=2, US-FEDWIRE=1"))
        .andExpect(jsonPath("$.is_business_day").value(true))
        .andExpect(jsonPath("$.combine").value("any_open"))
        .andExpect(jsonPath("$.calendars", hasSize(2)))
        .andExpect(jsonPath("$.calendars[1].calendar_id").value("EU-T2"))
        .andExpect(jsonPath("$.calendars[1].is_open").value(false));
  }

  @Test
  void shouldAddBusinessDaysAndExplainEverySkippedDay() throws Exception {
    postJson(
            """
            {"calendars": ["US-FEDWIRE", "EU-T2"], "date": "2026-11-24", "days": 2}
            """,
            "/v1/business-days/add")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value("2026-11-24"))
        .andExpect(jsonPath("$.days").value(2))
        .andExpect(jsonPath("$.date").value("2026-11-27"))
        .andExpect(jsonPath("$.combine").value("all_open"))
        .andExpect(jsonPath("$.skipped[*].date").value(contains("2026-11-26")))
        .andExpect(jsonPath("$.skipped[0].calendars[0].reason").value("Thanksgiving Day"))
        .andExpect(jsonPath("$.calendars[*].is_open").value(contains(true, true)));
  }

  @Test
  void shouldFindTheNextBusinessDay() throws Exception {
    postJson(
            """
            {"calendars": ["US-FEDWIRE"], "date": "2026-11-25"}
            """,
            "/v1/business-days/next")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value("2026-11-27"))
        .andExpect(jsonPath("$.days").value(1))
        .andExpect(jsonPath("$.skipped[*].date").value(contains("2026-11-26")));
  }

  @Test
  void shouldReportAnUnknownCalendar() throws Exception {
    problem(mvc.perform(get("/v1/calendars/XX-NOWHERE/days/2026-11-26")), 404, "unknown_calendar");
    problem(
        postJson(
            "{\"calendars\": [\"XX-NOWHERE\"], \"date\": \"2026-11-26\"}",
            "/v1/business-days/next"),
        404,
        "unknown_calendar");
  }

  @Test
  void shouldNeverGuessBeyondCoverage() throws Exception {
    problem(mvc.perform(get("/v1/calendars/US-FEDWIRE/days/2099-01-02")), 422, "out_of_range")
        .andExpect(jsonPath("$.calendar_id").value("US-FEDWIRE"))
        .andExpect(jsonPath("$.date").value("2099-01-02"));
    problem(
        mvc.perform(
            get("/v1/calendars/US-FEDWIRE/closures")
                .param("from", "2028-06-01")
                .param("to", "2029-01-31")),
        422,
        "out_of_range");
    problem(
        postJson(
            "{\"calendars\": [\"US-FEDWIRE\"], \"date\": \"2028-12-29\", \"days\": 2}",
            "/v1/business-days/add"),
        422,
        "out_of_range");
  }

  @Test
  void shouldRejectMalformedPathsAndParameters() throws Exception {
    problem(mvc.perform(get("/v1/calendars/us-fedwire/days/2026-11-26")), 400, "validation_failed");
    problem(mvc.perform(get("/v1/calendars/US-FEDWIRE/days/2026-13-01")), 400, "validation_failed");
    problem(
        mvc.perform(get("/v1/calendars/US-FEDWIRE/days/2026-11-26").param("as_of", "yesterday")),
        400,
        "validation_failed");
    problem(
        mvc.perform(get("/v1/calendars/US-FEDWIRE/closures").param("from", "2026-01-01")),
        400,
        "validation_failed");
  }

  @Test
  void shouldRejectClosureRangesThatAreBackwardsOrTooLong() throws Exception {
    problem(
        mvc.perform(
            get("/v1/calendars/US-FEDWIRE/closures")
                .param("from", "2026-02-01")
                .param("to", "2026-01-01")),
        400,
        "validation_failed");
    problem(
        mvc.perform(
            get("/v1/calendars/US-FEDWIRE/closures")
                .param("from", "2026-01-01")
                .param("to", "2028-01-02")),
        400,
        "validation_failed");
  }

  @Test
  void shouldRejectMalformedBodies() throws Exception {
    String add = "/v1/business-days/add";
    problem(postJson("{not json", add), 400, "validation_failed");
    problem(
        postJson("{\"calendars\": [], \"date\": \"2026-11-24\", \"days\": 1}", add),
        400,
        "validation_failed");
    problem(
        postJson(
            "{\"calendars\": [\"US-FEDWIRE\", \"US-FEDWIRE\"], \"date\": \"2026-11-24\", \"days\": 1}",
            add),
        400,
        "validation_failed");
    problem(
        postJson("{\"calendars\": [\"US-FEDWIRE\"], \"date\": \"2026-11-24\"}", add),
        400,
        "validation_failed");
    problem(
        postJson(
            "{\"calendars\": [\"US-FEDWIRE\"], \"date\": \"2026-11-24\", \"days\": 1001}", add),
        400,
        "validation_failed");
    problem(
        postJson(
            "{\"calendars\": [\"US-FEDWIRE\"], \"date\": \"2026-11-24\", \"days\": 1, \"combine\": \"most_open\"}",
            add),
        400,
        "validation_failed");
    problem(
        postJson(
            "{\"calendars\": [\"US-FEDWIRE\"], \"date\": \"2026-11-24\", \"days\": 1, \"extra\": true}",
            add),
        400,
        "validation_failed");
  }

  @Test
  void shouldRateLimitWithRetryAfter() throws Exception {
    when(rateLimiter.tryConsume(anyLong(), any()))
        .thenReturn(new Allowance.Denied(60, Duration.ofMillis(61_500)));

    problem(mvc.perform(get("/v1/calendars")), 429, "rate_limited")
        .andExpect(header().string("Retry-After", "62"))
        .andExpect(header().string("RateLimit-Limit", "60"))
        .andExpect(header().string("RateLimit-Remaining", "0"));
  }

  @Test
  void shouldSendRateLimitHeadersWhenAllowed() throws Exception {
    mvc.perform(get("/v1/calendars"))
        .andExpect(header().string("RateLimit-Limit", "60"))
        .andExpect(header().string("RateLimit-Remaining", "59"));
  }

  @Test
  void shouldAcceptAValidApiKey() throws Exception {
    when(apiKeys.findActiveByPrefix(KEY_PREFIX))
        .thenReturn(
            Optional.of(new StoredApiKey(UUID.randomUUID(), UUID.randomUUID(), secretHash())));
    when(rateLimiter.tryConsume(anyLong(), eq(new Limit(10_000, Duration.ofDays(1)))))
        .thenReturn(new Allowance.Allowed(10_000, 9_999));

    mvc.perform(get("/v1/calendars").header("Authorization", "Bearer " + KEY))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Limit", "10000"));
  }

  @Test
  void shouldRejectBadApiKeys() throws Exception {
    when(apiKeys.findActiveByPrefix(KEY_PREFIX))
        .thenReturn(
            Optional.of(new StoredApiKey(UUID.randomUUID(), UUID.randomUUID(), "00".repeat(32))));

    problem(
        mvc.perform(get("/v1/calendars").header("Authorization", "Bearer " + KEY)),
        401,
        "invalid_api_key");
    problem(
        mvc.perform(get("/v1/calendars").header("Authorization", "Bearer nope")),
        401,
        "invalid_api_key");
    problem(
            mvc.perform(get("/v1/calendars").header("Authorization", "Basic " + KEY)),
            401,
            "invalid_api_key")
        .andExpect(content().string(not(containsString(KEY_SECRET))));
  }

  @Test
  void shouldSayWhenNoReleaseHasBeenCut() throws Exception {
    when(datasets.latestRelease()).thenReturn(Optional.empty());

    problem(mvc.perform(get("/v1/calendars")), 503, "dataset_unavailable");
  }

  @Test
  void shouldReportUnknownRoutes() throws Exception {
    problem(mvc.perform(get("/v1/nowhere")), 404, "not_found");
  }

  @Test
  void shouldHideUnexpectedErrors() throws Exception {
    when(datasets.latestRelease())
        .thenThrow(new IllegalStateException("database password is hunter2"));

    problem(mvc.perform(get("/v1/calendars")), 500, "internal_error")
        .andExpect(content().string(not(containsString("hunter2"))));
  }

  @Test
  void shouldServeExactlyTheOperationsInOpenApi() throws Exception {
    Map<String, Map<String, Object>> paths;
    try (Reader reader = Files.newBufferedReader(Path.of("openapi.yaml"))) {
      Map<String, Object> spec = new Yaml().load(reader);
      paths = castPaths(spec.get("paths"));
    }
    Set<String> documented = new TreeSet<>();
    paths.forEach(
        (path, operations) ->
            operations
                .keySet()
                .forEach(method -> documented.add(method.toUpperCase(Locale.ROOT) + " " + path)));
    Set<String> served = new TreeSet<>();
    mappings
        .getHandlerMethods()
        .keySet()
        .forEach(
            info ->
                info.getPatternValues().stream()
                    .filter(
                        pattern ->
                            pattern.startsWith("/v1/")
                                || pattern.startsWith("/internal/")
                                || pattern.startsWith("/.well-known/"))
                    .forEach(
                        pattern ->
                            info.getMethodsCondition()
                                .getMethods()
                                .forEach(method -> served.add(method.name() + " " + pattern))));

    assertThat(served).isEqualTo(documented);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> castPaths(Object paths) {
    return (Map<String, Map<String, Object>>) paths;
  }

  private ResultActions postJson(String body, String path) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static ResultActions problem(ResultActions result, int status, String code)
      throws Exception {
    return result
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.title").isString());
  }

  private static String secretHash() {
    return HexFormat.of().formatHex(ApiKeyToken.sha256(KEY_SECRET));
  }
}
