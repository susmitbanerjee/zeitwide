package com.zeitwide.api.answers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.TestApiKeys;
import com.zeitwide.api.answers.domain.ConformanceRunner;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The whole serving path on real Postgres: the golden dataset loaded as the pipeline writes it,
 * every conformance case sent as an HTTP request, and the latency of those requests.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Hundreds of requests on one key; the limits have their own test.
    properties = "zeitwide.rate-limits.keyed.capacity=1000000")
@Import(PostgresTestcontainer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnswersHttpTest {

  private static final Logger log = LoggerFactory.getLogger(AnswersHttpTest.class);
  private static final long P95_BUDGET_MILLIS = 50;
  private static final int WARM_UP_ROUNDS = 3;
  private static final int MEASURED_ROUNDS = 10;
  private static final double P95 = 0.95;
  private static final int OK = 200;

  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;

  private String apiKey;

  @BeforeAll
  void loadGoldenDataset() {
    GoldenDatabase.load(jdbc);
    apiKey = TestApiKeys.create(jdbc);
  }

  @TestFactory
  Stream<DynamicTest> shouldPassEveryConformanceCaseOverHttp() {
    return ConformanceRunner.casePaths().stream()
        .map(path -> DynamicTest.dynamicTest(name(path), () -> assertPassesOverHttp(path)));
  }

  @Test
  void shouldAnswerWithinTheLatencyBudgetAtP95() throws Exception {
    List<HttpRequest> requests = new ArrayList<>();
    for (Path path : ConformanceRunner.casePaths()) {
      requests.add(request(ConformanceRunner.readCase(path).path("input")));
    }
    for (int round = 0; round < WARM_UP_ROUNDS; round++) {
      send(requests);
    }
    List<Long> nanos = new ArrayList<>();
    for (int round = 0; round < MEASURED_ROUNDS; round++) {
      nanos.addAll(send(requests));
    }

    long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
    double p50 = sorted[sorted.length / 2] / 1e6;
    double p95 = sorted[(int) Math.ceil(sorted.length * P95) - 1] / 1e6;
    log.atInfo()
        .addKeyValue("requests", sorted.length)
        .addKeyValue("p50_ms", p50)
        .addKeyValue("p95_ms", p95)
        .log("Answer latency over {} requests: p50 {} ms, p95 {} ms", sorted.length, p50, p95);
    assertThat(p95)
        .as("p95 over %d requests (p50 %.2f ms)", sorted.length, p50)
        .isLessThan(P95_BUDGET_MILLIS);
  }

  private List<Long> send(List<HttpRequest> requests) throws IOException, InterruptedException {
    List<Long> nanos = new ArrayList<>();
    for (HttpRequest request : requests) {
      long start = System.nanoTime();
      http.send(request, HttpResponse.BodyHandlers.discarding());
      nanos.add(System.nanoTime() - start);
    }
    return nanos;
  }

  private void assertPassesOverHttp(Path path) throws IOException, InterruptedException {
    JsonNode testCase = ConformanceRunner.readCase(path);
    HttpResponse<String> response =
        http.send(request(testCase.path("input")), HttpResponse.BodyHandlers.ofString());
    JsonNode body = json.readTree(response.body());

    assertThat(answer(testCase.path("input"), response.statusCode(), body))
        .as("%s: HTTP %d %s", path.getFileName(), response.statusCode(), response.body())
        .isEqualTo(testCase.path("expected"));
  }

  /** The case's request, as a person would send it: GET for one calendar, POST otherwise. */
  private HttpRequest request(JsonNode input) throws IOException {
    String operation = input.path("operation").asText();
    JsonNode calendars = input.path("calendars");
    boolean singleDay =
        operation.equals("is_business_day")
            && calendars.size() == 1
            && input.path("combine").isMissingNode();
    if (singleDay) {
      String query =
          input.has("as_of")
              ? "?as_of=" + URLEncoder.encode(input.path("as_of").asText(), StandardCharsets.UTF_8)
              : "";
      return HttpRequest.newBuilder(
              uri(
                  "/v1/calendars/"
                      + calendars.get(0).asText()
                      + "/days/"
                      + input.path("date").asText()
                      + query))
          .header("Authorization", TestApiKeys.bearer(apiKey))
          .GET()
          .build();
    }
    ObjectNode body = json.createObjectNode();
    body.set("calendars", calendars);
    body.set("date", input.path("date"));
    for (String field : List.of("combine", "as_of", "days")) {
      if (input.has(field)) {
        body.set(field, input.path(field));
      }
    }
    String endpoint =
        switch (operation) {
          case "is_business_day" -> "check";
          case "next_business_day" -> "next";
          default -> "add";
        };
    return HttpRequest.newBuilder(uri("/v1/business-days/" + endpoint))
        .header("Authorization", TestApiKeys.bearer(apiKey))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
  }

  /** The response in the case's terms: the answer, or the error code. */
  private JsonNode answer(JsonNode input, int status, JsonNode body) {
    ObjectNode answer = json.createObjectNode();
    if (status != OK) {
      return answer.put("error", body.path("code").asText());
    }
    if (input.path("operation").asText().equals("is_business_day")) {
      return answer.put("is_business_day", body.path("is_business_day").asBoolean());
    }
    answer.put("date", body.path("date").asText());
    ArrayNode skipped = answer.putArray("skipped");
    body.path("skipped").forEach(day -> skipped.add(day.path("date").asText()));
    return answer;
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static String name(Path path) {
    return path.getFileName().toString().replace(".json", "");
  }
}
