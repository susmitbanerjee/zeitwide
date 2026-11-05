package com.zeitwide.api.a2a.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeitwide.api.a2a.domain.Question;
import com.zeitwide.api.a2a.domain.QuestionParser;
import com.zeitwide.api.a2a.domain.UnreadableQuestionException;
import com.zeitwide.api.answers.api.SettlementAnswers;
import com.zeitwide.api.answers.app.DatasetUnavailableException;
import com.zeitwide.api.answers.domain.AnswerException;
import com.zeitwide.api.auth.domain.Caller;
import com.zeitwide.api.ratelimits.api.RequestCaller;
import com.zeitwide.api.ratelimits.app.RateLimits;
import com.zeitwide.api.ratelimits.domain.Allowance;
import com.zeitwide.api.web.RequestValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The A2A 1.0 JSON-RPC binding, as a thin adapter over the answer engine: {@code SendMessage} takes
 * one message and returns one message, a sentence citing the sources plus the full answer as JSON.
 * Nothing runs after the reply, so there are no tasks, streams or push notifications.
 */
@RestController
class A2aController {

  static final String VERSION = "1.0";
  static final String VERSION_HEADER = "A2A-Version";
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final SettlementAnswers answers;
  private final RateLimits rateLimits;
  private final ObjectMapper json;

  A2aController(SettlementAnswers answers, RateLimits rateLimits, ObjectMapper json) {
    this.answers = answers;
    this.rateLimits = rateLimits;
    this.json = json;
  }

  @PostMapping("/a2a")
  ResponseEntity<JsonNode> rpc(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = VERSION_HEADER, required = false) String version,
      HttpServletRequest request) {
    JsonNode call;
    try {
      call = body == null ? null : json.readTree(body);
    } catch (IOException malformed) {
      return reply(JsonRpcError.PARSE_ERROR.response(null, "Invalid JSON payload"));
    }
    if (call == null
        || !call.isObject()
        || !"2.0".equals(call.path("jsonrpc").asText(null))
        || !call.path("method").isTextual()) {
      return reply(
          JsonRpcError.INVALID_REQUEST.response(id(call), "Request payload validation error"));
    }
    JsonNode id = id(call);
    String method = call.get("method").asText();
    if (!supportedVersion(version, method)) {
      return reply(
          JsonRpcError.VERSION_NOT_SUPPORTED.response(
              id, "This agent speaks A2A 1.0: send the header A2A-Version: 1.0 and SendMessage"));
    }
    return switch (method) {
      case "SendMessage" -> sendMessage(id, call.path("params"), request);
      case "GetTask", "CancelTask", "SubscribeToTask" ->
          reply(JsonRpcError.TASK_NOT_FOUND.response(id, "This agent keeps no tasks"));
      case "GetExtendedAgentCard" ->
          reply(
              JsonRpcError.EXTENDED_CARD_NOT_CONFIGURED.response(
                  id, "There is no extended agent card"));
      case "SendStreamingMessage",
          "ListTasks",
          "CreateTaskPushNotificationConfig",
          "GetTaskPushNotificationConfig",
          "ListTaskPushNotificationConfigs",
          "DeleteTaskPushNotificationConfig" ->
          reply(
              JsonRpcError.UNSUPPORTED_OPERATION.response(
                  id, "Only SendMessage is supported: one message in, one answer out"));
      default -> reply(JsonRpcError.METHOD_NOT_FOUND.response(id, "Method not found"));
    };
  }

  /** 1.0 only. A missing header means 0.3, unless the method is a 1.0 name: then take it as 1.0. */
  private static boolean supportedVersion(String version, String method) {
    if (version == null || version.isBlank()) {
      return Character.isUpperCase(method.charAt(0));
    }
    return VERSION.equals(version.trim()) || "1".equals(version.trim());
  }

  private ResponseEntity<JsonNode> sendMessage(
      JsonNode id, JsonNode params, HttpServletRequest request) {
    JsonNode message = params.path("message");
    if (!message.path("parts").isArray() || message.path("parts").isEmpty()) {
      return reply(JsonRpcError.INVALID_PARAMS.response(id, "params.message.parts is required"));
    }
    Optional<Caller> caller = RequestCaller.of(request);
    if (caller.isEmpty()) {
      return reply(JsonRpcError.INTERNAL.response(id, "The caller could not be identified"));
    }
    if (caller.get() instanceof Caller.Anonymous anonymous
        && rateLimits.consumeAnonymousCall(anonymous) instanceof Allowance.Denied denied) {
      long seconds = Math.max(1, denied.retryAfter().toSeconds());
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              JsonRpcError.INTERNAL.response(
                  id,
                  "The anonymous limit of "
                      + denied.limit()
                      + " messages a day for this network is used up. Try again in "
                      + seconds
                      + " seconds, or send an API key."));
    }
    if (!readable(message.path("parts"))) {
      return reply(
          JsonRpcError.CONTENT_TYPE_NOT_SUPPORTED.response(
              id, "This agent reads text/plain and application/json parts only"));
    }
    try {
      Question question = question(message.path("parts"));
      SettlementAnswers.Reply reply = answer(question, !(caller.get() instanceof Caller.Anonymous));
      return reply(success(id, message, reply));
    } catch (UnreadableQuestionException | RequestValidationException unreadable) {
      return reply(JsonRpcError.INVALID_PARAMS.response(id, unreadable.getMessage()));
    } catch (AnswerException unanswerable) {
      return reply(
          JsonRpcError.INVALID_PARAMS.response(id, unanswerable.getMessage(), unanswerable.code()));
    } catch (DatasetUnavailableException unavailable) {
      return reply(
          JsonRpcError.INTERNAL.response(
              id, "No dataset release is currently available", "dataset_unavailable"));
    }
  }

  private static boolean readable(JsonNode parts) {
    for (JsonNode part : parts) {
      if (part.path("data").isObject() || part.path("text").isTextual()) {
        return true;
      }
    }
    return false;
  }

  /** The first JSON data part if there is one, else the text parts joined. */
  private Question question(JsonNode parts) {
    QuestionParser parser = new QuestionParser(answers.calendarIds());
    StringBuilder text = new StringBuilder();
    for (JsonNode part : parts) {
      if (part.path("data").isObject()) {
        return parser.parseData(json.convertValue(part.get("data"), Map.class));
      }
      if (part.path("text").isTextual()) {
        text.append(part.get("text").asText()).append('\n');
      }
    }
    return parser.parseText(text.toString().strip());
  }

  private SettlementAnswers.Reply answer(Question question, boolean signed) {
    return switch (question) {
      case Question.Check check -> answers.check(check.calendars(), check.date(), signed);
      case Question.Next next -> answers.next(next.calendars(), next.date(), signed);
      case Question.Add add -> answers.add(add.calendars(), add.date(), add.days(), signed);
      case Question.Closures closures ->
          answers.closures(closures.calendar(), closures.from(), closures.to(), signed);
    };
  }

  private ObjectNode success(JsonNode id, JsonNode request, SettlementAnswers.Reply reply) {
    ObjectNode message = NODES.objectNode();
    message.put("messageId", UUID.randomUUID().toString());
    String contextId = request.path("contextId").asText("");
    message.put("contextId", contextId.isEmpty() ? UUID.randomUUID().toString() : contextId);
    message.put("role", "ROLE_AGENT");
    var parts = message.putArray("parts");
    parts.addObject().put("text", reply.text()).put("mediaType", MediaType.TEXT_PLAIN_VALUE);
    ObjectNode data = parts.addObject();
    data.set("data", json.valueToTree(reply.answer()));
    data.put("mediaType", MediaType.APPLICATION_JSON_VALUE);
    ObjectNode response = NODES.objectNode().put("jsonrpc", "2.0");
    response.set("id", id);
    response.putObject("result").set("message", message);
    return response;
  }

  private static JsonNode id(JsonNode call) {
    JsonNode id = call == null ? null : call.get("id");
    return id != null && (id.isTextual() || id.isNumber()) ? id : NODES.nullNode();
  }

  private static ResponseEntity<JsonNode> reply(JsonNode body) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(VERSION_HEADER, VERSION)
        .body(body);
  }
}
