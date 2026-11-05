package com.zeitwide.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.zeitwide.api.PostgresTestcontainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Every discovery document: 200, its media type, open CORS, caching and a valid body against the
 * spec's schema in conformance/discovery. Also checks server.json, which the registry workflow
 * publishes, against the MCP Registry's schema and against the live server card.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zeitwide.discovery.site-url=https://zeitwide.com",
      "zeitwide.discovery.api-url=https://api.zeitwide.com",
      "zeitwide.rate-limits.anonymous.capacity=1000000"
    })
@Import(PostgresTestcontainer.class)
class DiscoveryHttpTest {

  private static final Path SCHEMAS = Path.of("..", "conformance", "discovery");
  private static final String ORIGIN = "https://agents.example";

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;

  static List<Arguments> documents() {
    return List.of(
        Arguments.of("/v1/server-card", "application/mcp-server-card+json", "server-card"),
        Arguments.of("/mcp/server-card", "application/mcp-server-card+json", "server-card"),
        Arguments.of("/.well-known/agent-card.json", "application/json", "a2a-agent-card"),
        Arguments.of("/.well-known/ai-catalog.json", "application/ai-catalog+json", "ai-catalog"),
        Arguments.of("/.well-known/zeitwide-keys.json", "application/json", "zeitwide-keys"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("documents")
  void shouldServeAValidPublicDocument(String path, String type, String schema) throws Exception {
    HttpResponse<String> response = get(path, builder -> {});

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith(type);
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
    assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
        .contains("max-age=3600", "public");
    assertThat(errors(schema, json.readTree(response.body()))).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("documents")
  void shouldAllowAnyOriginToPreflight(String path, String type, String schema) throws Exception {
    HttpRequest preflight =
        HttpRequest.newBuilder(uri(path))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", ORIGIN)
            .header("Access-Control-Request-Method", "GET")
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(preflight, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
  }

  @Test
  void shouldAnswerConditionalRequestsWithNotModified() throws Exception {
    String etag = get("/v1/server-card", builder -> {}).headers().firstValue("ETag").orElseThrow();
    HttpResponse<String> again =
        get("/v1/server-card", builder -> builder.header("If-None-Match", etag));
    assertThat(again.statusCode()).isEqualTo(304);
  }

  @Test
  void shouldDescribeTheServerAsItReportsItself() throws Exception {
    JsonNode card = json.readTree(get("/v1/server-card", builder -> {}).body());
    assertThat(card.path("name").asText()).isEqualTo("io.github.susmitbanerjee/zeitwide");
    JsonNode remote = card.path("remotes").get(0);
    assertThat(remote.path("type").asText()).isEqualTo("streamable-http");
    assertThat(remote.path("url").asText()).isEqualTo("https://api.zeitwide.com/mcp");
    assertThat(texts(remote.path("supportedProtocolVersions"))).contains("2025-06-18");
    assertThat(remote.path("headers").get(0).path("isRequired").asBoolean(true)).isFalse();
  }

  @Test
  void shouldLinkEveryEntryPointFromTheCatalog() throws Exception {
    JsonNode catalog = json.readTree(get("/.well-known/ai-catalog.json", builder -> {}).body());
    assertThat(catalog.path("entries").findValuesAsText("url"))
        .containsExactlyInAnyOrder(
            "https://api.zeitwide.com/v1/server-card",
            "https://zeitwide.com/.well-known/agent-card.json",
            "https://zeitwide.com/openapi.json",
            "https://zeitwide.com/llms.txt",
            "https://zeitwide.com/docs/llms-full.txt",
            "https://zeitwide.com/.well-known/zeitwide-keys.json");
  }

  @Test
  void shouldOfferTheSettlementSkillOverJsonRpc() throws Exception {
    JsonNode card = json.readTree(get("/.well-known/agent-card.json", builder -> {}).body());
    JsonNode skill = card.path("skills").get(0);
    assertThat(skill.path("id").asText()).isEqualTo("settlement-date-answers");
    assertThat(texts(skill.path("examples")))
        .contains(
            "Is 2026-12-28 a CHAPS business day?",
            "T+2 from 2026-12-24 on US-FEDWIRE and GB-CHAPS");
    assertThat(texts(card.path("defaultInputModes")))
        .containsExactly("text/plain", "application/json");
    JsonNode endpoint = card.path("supportedInterfaces").get(0);
    assertThat(endpoint.path("url").asText()).isEqualTo("https://api.zeitwide.com/a2a");
    assertThat(endpoint.path("protocolBinding").asText()).isEqualTo("JSONRPC");
  }

  @Test
  void shouldListKeysWithTheirAlgorithm() throws Exception {
    JsonNode keys = json.readTree(get("/.well-known/zeitwide-keys.json", builder -> {}).body());
    assertThat(keys.path("keys"))
        .allSatisfy(
            key -> {
              assertThat(key.path("alg").asText()).isEqualTo("Ed25519");
              assertThat(key.path("kid").asText()).isEqualTo(key.path("key_id").asText());
            });
  }

  @Test
  void shouldKeepServerJsonValidAndInStepWithTheServerCard() throws Exception {
    JsonNode server = json.readTree(Files.readString(Path.of("..", "server.json")));
    Schema registry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
            .getSchema(Files.readString(SCHEMAS.resolve("mcp-registry-server.schema.json")));
    assertThat(registry.validate(server)).extracting(Error::toString).isEmpty();

    JsonNode card = json.readTree(get("/v1/server-card", builder -> {}).body());
    for (String field : List.of("name", "title", "description", "websiteUrl", "repository")) {
      assertThat(server.path(field)).as(field).isEqualTo(card.path(field));
    }
    assertThat(server.path("remotes").get(0).path("url"))
        .isEqualTo(card.path("remotes").get(0).path("url"));
    assertThat(server.path("packages").get(0).path("identifier").asText())
        .isEqualTo("zeitwide-mcp");
  }

  private List<String> errors(String name, JsonNode document) throws IOException {
    JsonNode schema = json.readTree(Files.readString(SCHEMAS.resolve(name + ".schema.json")));
    // The server card schema is a bundle of $defs with no root; point its root at ServerCard.
    if (!schema.has("$ref") && !schema.has("type") && schema.path("$defs").has("ServerCard")) {
      ((ObjectNode) schema).put("$ref", "#/$defs/ServerCard");
    }
    Schema compiled =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> {})
            .getSchema(json.writeValueAsString(schema), InputFormat.JSON);
    return compiled.validate(document).stream().map(Error::toString).toList();
  }

  private static List<String> texts(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false)
        .filter(JsonNode::isTextual)
        .map(JsonNode::asText)
        .toList();
  }

  private HttpResponse<String> get(String path, Consumer<HttpRequest.Builder> customize)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("Origin", ORIGIN);
    customize.accept(builder);
    return HttpClient.newHttpClient()
        .send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
