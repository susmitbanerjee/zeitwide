package com.zeitwide.api.ratelimits;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.answers.GoldenDatabase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Anonymous MCP tool calls and A2A messages share one small daily allowance per network, counted
 * per answer rather than per HTTP request, so connecting and listing tools cost nothing.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zeitwide.rate-limits.anonymous.capacity=1000000",
      "zeitwide.rate-limits.anonymous-calls.capacity=2"
    })
@Import(PostgresTestcontainer.class)
class AnonymousCallLimitHttpTest {

  private static final Map<String, Object> CHECK =
      Map.of(
          "calendars", List.of("GB-CHAPS"),
          "date", "2026-12-28",
          "as_of", "2026-10-29T00:00:00Z");

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;

  @Test
  void shouldShareTheDailyAllowanceAcrossMcpAndA2a() throws Exception {
    GoldenDatabase.load(jdbc);
    try (McpSyncClient client =
        McpClient.sync(HttpClientStreamableHttpTransport.builder(uri("/mcp").toString()).build())
            .build()) {
      client.initialize();
      client.listTools();
      assertThat(call(client).isError()).isNotEqualTo(true);
      assertThat(call(client).isError()).isNotEqualTo(true);
      McpSchema.CallToolResult limited = call(client);
      assertThat(limited.isError()).isTrue();
      assertThat(limited.structuredContent()).isInstanceOf(Map.class);
      assertThat(((Map<?, ?>) limited.structuredContent()).get("code")).isEqualTo("rate_limited");
    }

    HttpRequest message =
        HttpRequest.newBuilder(uri("/a2a"))
            .header("Content-Type", "application/json")
            .header("A2A-Version", "1.0")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":"
                        + "{\"message\":{\"messageId\":\"m1\",\"role\":\"ROLE_USER\",\"parts\":"
                        + "[{\"text\":\"Is 2026-12-28 a CHAPS business day?\"}]}}}"))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(message, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(response.headers().firstValue("Retry-After")).isPresent();
    assertThat(response.body()).contains("\"code\":-32603");
  }

  private static McpSchema.CallToolResult call(McpSyncClient client) {
    return client.callTool(new McpSchema.CallToolRequest("is_business_day", CHECK));
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
