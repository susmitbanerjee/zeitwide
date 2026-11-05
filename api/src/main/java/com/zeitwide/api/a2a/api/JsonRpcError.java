package com.zeitwide.api.a2a.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** JSON-RPC 2.0 and A2A 1.0 error codes (A2A spec sections 5.4 and 9.5). */
enum JsonRpcError {
  PARSE_ERROR(-32_700),
  INVALID_REQUEST(-32_600),
  METHOD_NOT_FOUND(-32_601),
  INVALID_PARAMS(-32_602),
  INTERNAL(-32_603),
  TASK_NOT_FOUND(-32_001),
  UNSUPPORTED_OPERATION(-32_004),
  CONTENT_TYPE_NOT_SUPPORTED(-32_005),
  EXTENDED_CARD_NOT_CONFIGURED(-32_007),
  VERSION_NOT_SUPPORTED(-32_009);

  private static final String ERROR_INFO = "type.googleapis.com/google.rpc.ErrorInfo";
  private static final String DOMAIN = "zeitwide.com";

  private final int code;

  JsonRpcError(int code) {
    this.code = code;
  }

  ObjectNode response(JsonNode id, String message) {
    return response(id, message, name().toLowerCase(Locale.ROOT));
  }

  /** {@code reason}: a stable machine code, the same as in the REST problem details. */
  ObjectNode response(JsonNode id, String message, String reason) {
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    ObjectNode response = nodes.objectNode().put("jsonrpc", "2.0");
    response.set("id", id == null ? nodes.nullNode() : id);
    ObjectNode error = response.putObject("error").put("code", code).put("message", message);
    error
        .putArray("data")
        .addObject()
        .put("@type", ERROR_INFO)
        .put("reason", reason.toUpperCase(Locale.ROOT))
        .put("domain", DOMAIN);
    return response;
  }
}
