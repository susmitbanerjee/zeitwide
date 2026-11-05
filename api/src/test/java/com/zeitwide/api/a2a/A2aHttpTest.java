package com.zeitwide.api.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.answers.GoldenDatabase;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** An A2A 1.0 client, speaking JSON-RPC over HTTP to /a2a as the agent card says to. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zeitwide.rate-limits.anonymous.capacity=1000000",
      "zeitwide.rate-limits.anonymous-calls.capacity=1000000"
    })
@Import(PostgresTestcontainer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class A2aHttpTest {

  // A made-up test key, never a real credential.
  private static final String API_KEY_PREFIX = "zw_live_A2aTest1"; // gitleaks:allow
  private static final String API_KEY_SECRET = "C".repeat(43); // gitleaks:allow
  private static final String API_KEY = API_KEY_PREFIX + "_" + API_KEY_SECRET;

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @BeforeAll
  void loadDatasetAndApiKey() {
    GoldenDatabase.load(jdbc);
    UUID accountId =
        jdbc.sql("INSERT INTO account (name) VALUES ('A2A test') RETURNING id")
            .query(UUID.class)
            .single();
    jdbc.sql(
            "INSERT INTO api_key (account_id, name, prefix, secret_sha256) "
                + "VALUES (:account, 'A2A test', :prefix, :hash)")
        .param("account", accountId)
        .param("prefix", API_KEY_PREFIX)
        .param("hash", ApiKeyToken.sha256(API_KEY_SECRET))
        .update();
  }

  @Test
  void shouldAnswerTheChapsQuestionWithSourcesAndNoCredentials() throws Exception {
    HttpResponse<String> response = send(text("Is 2026-12-28 a CHAPS business day?"), null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
    JsonNode message = json.readTree(response.body()).path("result").path("message");
    assertThat(message.path("role").asText()).isEqualTo("ROLE_AGENT");
    assertThat(message.path("messageId").asText()).isNotBlank();
    JsonNode parts = message.path("parts");
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).path("text").asText())
        .startsWith("No: 2026-12-28 is not a business day on GB-CHAPS")
        .contains("Boxing Day (Substitute day)")
        // The golden dataset cites an archived copy of the GOV.UK file.
        .containsPattern(
            "Sources: GOV\\.UK \\(Government Digital Service\\) https://\\S*www\\.gov\\.uk/"
                + "bank-holidays\\.json")
        .contains("Dataset release 2026.10.29.0");
    assertThat(parts.get(0).path("mediaType").asText()).isEqualTo("text/plain");
    JsonNode answer = parts.get(1).path("data");
    assertThat(parts.get(1).path("mediaType").asText()).isEqualTo("application/json");
    assertThat(answer.path("is_business_day").asBoolean(true)).isFalse();
    assertThat(answer.path("dataset_release").asText()).isEqualTo("2026.10.29.0");
    assertThat(answer.path("calendars").get(0).path("sources").get(0).path("url").asText())
        .endsWith("www.gov.uk/bank-holidays.json");
    assertThat(answer.has("receipt")).isFalse();
  }

  @Test
  void shouldAnswerTPlusTwoOnTwoRails() throws Exception {
    JsonNode message = result(send(text("T+2 from 2026-12-24 on US-FEDWIRE and GB-CHAPS"), null));
    JsonNode answer = message.path("parts").get(1).path("data");
    assertThat(answer.path("start").asText()).isEqualTo("2026-12-24");
    assertThat(answer.path("days").asInt()).isEqualTo(2);
    assertThat(answer.path("calendars").findValuesAsText("calendar_id"))
        .contains("US-FEDWIRE", "GB-CHAPS");
    assertThat(message.path("parts").get(0).path("text").asText())
        .startsWith(
            "T+2 from 2026-12-24 on US-FEDWIRE and GB-CHAPS is " + answer.path("date").asText())
        .contains("Skipped 2026-12-25");
  }

  @Test
  void shouldReadJsonQuestionsAndSignThemForKeyedCallers() throws Exception {
    Map<String, Object> data =
        Map.of(
            "operation", "list_closures",
            "calendar_id", "GB-CHAPS",
            "from", "2026-12-01",
            "to", "2026-12-31");
    JsonNode message = result(send(request(List.of(Map.of("data", data))), API_KEY));
    JsonNode answer = message.path("parts").get(1).path("data");
    assertThat(answer.path("closures").findValuesAsText("date"))
        .containsExactly("2026-12-25", "2026-12-28");
    assertThat(answer.path("receipt").path("signature").asText()).isNotBlank();
  }

  @Test
  void shouldExplainAQuestionItCannotRead() throws Exception {
    JsonNode error = error(send(text("Is Christmas a CHAPS holiday?"), null));
    assertThat(error.path("code").asInt()).isEqualTo(-32602);
    assertThat(error.path("message").asText()).contains("YYYY-MM-DD");
  }

  @Test
  void shouldRefuseTasksStreamsAndUnknownMethods() throws Exception {
    assertThat(error(send(call("GetTask", Map.of("id", "t1")), null)).path("code").asInt())
        .isEqualTo(-32001);
    assertThat(error(send(call("SendStreamingMessage", Map.of()), null)).path("code").asInt())
        .isEqualTo(-32004);
    assertThat(error(send(call("Nope", Map.of()), null)).path("code").asInt()).isEqualTo(-32601);
  }

  @Test
  void shouldRefuseOtherProtocolVersions() throws Exception {
    HttpRequest request =
        post(json.writeValueAsString(text("Is 2026-12-28 a CHAPS business day?")), null)
            .header("A2A-Version", "0.3")
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(error(response).path("code").asInt()).isEqualTo(-32009);
  }

  @Test
  void shouldReportMalformedJson() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(post("{not json", null).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(error(response).path("code").asInt()).isEqualTo(-32700);
  }

  @Test
  void shouldRefuseABadKeyRatherThanAnswerAnonymously() throws Exception {
    HttpResponse<String> response =
        send(text("Is 2026-12-28 a CHAPS business day?"), "zw_live_Nope0000_" + "D".repeat(43));
    assertThat(response.statusCode()).isEqualTo(401);
  }

  private Map<String, Object> text(String question) {
    return request(List.of(Map.of("text", question)));
  }

  private Map<String, Object> request(List<?> parts) {
    return call(
        "SendMessage",
        Map.of(
            "message",
            Map.of(
                "messageId", UUID.randomUUID().toString(), "role", "ROLE_USER", "parts", parts)));
  }

  private static Map<String, Object> call(String method, Map<String, Object> params) {
    return Map.of("jsonrpc", "2.0", "id", 7, "method", method, "params", params);
  }

  private HttpResponse<String> send(Map<String, Object> body, String apiKey) throws Exception {
    HttpRequest request = post(json.writeValueAsString(body), apiKey).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder post(String body, String apiKey) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/a2a"))
            .header("Content-Type", "application/json")
            .header("A2A-Version", "1.0")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (apiKey != null) {
      builder.header("Authorization", "Bearer " + apiKey);
    }
    return builder;
  }

  private JsonNode result(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    assertThat(body.has("error")).as(response.body()).isFalse();
    assertThat(body.path("id").asInt()).isEqualTo(7);
    return body.path("result").path("message");
  }

  private JsonNode error(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
    return body.path("error");
  }
}
