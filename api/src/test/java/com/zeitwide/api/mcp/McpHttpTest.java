package com.zeitwide.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.answers.GoldenDatabase;
import com.zeitwide.api.auth.domain.ApiKeyToken;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "zeitwide.rate-limits.anonymous.capacity=1000000")
@Import({PostgresTestcontainer.class, McpHttpTest.FixedClockConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpHttpTest {

  // A made-up test key, never a real credential.
  private static final String API_KEY_PREFIX = "zw_live_McpTest1"; // gitleaks:allow
  private static final String API_KEY_SECRET = "A".repeat(43); // gitleaks:allow
  private static final String API_KEY = API_KEY_PREFIX + "_" + API_KEY_SECRET;
  private static final String AS_OF = "2022-09-10T00:00:00Z";

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @BeforeAll
  void loadDatasetAndApiKey() {
    GoldenDatabase.load(jdbc);
    UUID accountId =
        jdbc.sql("INSERT INTO account (name) VALUES ('MCP test') RETURNING id")
            .query(UUID.class)
            .single();
    jdbc.sql(
            "INSERT INTO api_key (account_id, name, prefix, secret_sha256) "
                + "VALUES (:account, 'MCP test', :prefix, :hash)")
        .param("account", accountId)
        .param("prefix", API_KEY_PREFIX)
        .param("hash", ApiKeyToken.sha256(API_KEY_SECRET))
        .update();
  }

  @Test
  void inspectorDiscoversSixStrictToolsAndAllResultsMatchRestByteForByte() throws Exception {
    try (McpSyncClient client = client()) {
      client.initialize();
      assertThat(client.getServerInstructions())
          .contains("Never guess", "Cite", "Store the receipt", "get_changes");

      List<McpSchema.Tool> discovered = client.listTools().tools();
      assertThat(discovered)
          .extracting(McpSchema.Tool::name)
          .containsExactlyInAnyOrder(
              "is_business_day",
              "next_business_day",
              "add_business_days",
              "explain_date",
              "list_closures",
              "get_changes");
      assertThat(discovered)
          .allSatisfy(tool -> assertThat(tool.inputSchema().additionalProperties()).isFalse());

      Map<String, Object> dayRequest =
          Map.of("calendars", List.of("GB-CHAPS"), "date", "2022-09-19", "as_of", AS_OF);
      assertPostMatchesRest(
          client, "is_business_day", dayRequest, "/v1/business-days/check", dayRequest);
      assertPostMatchesRest(
          client, "next_business_day", dayRequest, "/v1/business-days/next", dayRequest);
      Map<String, Object> addRequest =
          Map.of("calendars", List.of("GB-CHAPS"), "date", "2022-09-19", "days", 2, "as_of", AS_OF);
      assertPostMatchesRest(
          client, "add_business_days", addRequest, "/v1/business-days/add", addRequest);

      Map<String, Object> explainRequest =
          Map.of("calendar_id", "GB-CHAPS", "date", "2022-09-19", "as_of", AS_OF);
      assertGetMatchesRest(
          client,
          "explain_date",
          explainRequest,
          "/v1/calendars/GB-CHAPS/days/2022-09-19?as_of=2022-09-10T00%3A00%3A00Z");
      Map<String, Object> closuresRequest =
          Map.of(
              "calendar_id", "GB-CHAPS", "from", "2022-09-01", "to", "2022-09-30", "as_of", AS_OF);
      assertGetMatchesRest(
          client,
          "list_closures",
          closuresRequest,
          "/v1/calendars/GB-CHAPS/closures?from=2022-09-01&to=2022-09-30&as_of=2022-09-10T00%3A00%3A00Z");
      Map<String, Object> changesRequest =
          Map.of(
              "calendars",
              List.of("GB-CHAPS"),
              "since",
              AS_OF,
              "types",
              List.of("closure_added"),
              "limit",
              25);
      assertGetMatchesRest(
          client,
          "get_changes",
          changesRequest,
          "/v1/changes?calendars=GB-CHAPS&since=2022-09-10T00%3A00%3A00Z&types=closure_added&limit=25");
    }
  }

  @Test
  void invalidToolArgumentsReturnAnMcpToolError() {
    try (McpSyncClient client = client()) {
      client.initialize();
      McpSchema.CallToolResult result =
          client.callTool(
              new McpSchema.CallToolRequest(
                  "is_business_day",
                  Map.of("calendars", List.of("UNKNOWN-CALENDAR"), "date", "2022-09-19")));
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).hasSize(1);
      assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
          .isEqualTo("One or more calendars are not in the dataset");
    }
  }

  private void assertPostMatchesRest(
      McpSyncClient client,
      String tool,
      Map<String, Object> arguments,
      String path,
      Object restBody)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(restBody)))
            .build();
    assertMatches(client, tool, arguments, request);
  }

  private void assertGetMatchesRest(
      McpSyncClient client, String tool, Map<String, Object> arguments, String path)
      throws Exception {
    assertMatches(
        client,
        tool,
        arguments,
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + API_KEY)
            .GET()
            .build());
  }

  private void assertMatches(
      McpSyncClient client, String tool, Map<String, Object> arguments, HttpRequest restRequest)
      throws Exception {
    HttpResponse<String> rest =
        HttpClient.newHttpClient().send(restRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(rest.statusCode()).isEqualTo(200);
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    assertThat(result.isError()).isNotEqualTo(true);
    String structuredJson = ((McpSchema.TextContent) result.content().getFirst()).text();
    assertThat(structuredJson.getBytes(StandardCharsets.UTF_8))
        .containsExactly(rest.body().getBytes(StandardCharsets.UTF_8));
  }

  private McpSyncClient client() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(uri("/mcp").toString())
            .customizeRequest(request -> request.header("Authorization", "Bearer " + API_KEY))
            .build();
    return McpClient.sync(transport)
        .clientInfo(new McpSchema.Implementation("zeitwide-test", "1.0"))
        .build();
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    }
  }
}
