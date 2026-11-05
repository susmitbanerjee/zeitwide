package com.zeitwide.api.discovery.api;

import com.zeitwide.api.discovery.app.DiscoveryProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three discovery documents, built from configuration only, so they never drift from the
 * server: the MCP Server Card (SEP-2127, modelcontextprotocol/ext-server-card), the A2A 1.0 Agent
 * Card, and the AI Catalog (Agent-Card/ai-catalog) that links them with the API's other entry
 * points. Field names are the specs' camelCase, so these are maps rather than records under the
 * API's snake_case naming.
 */
final class DiscoveryDocuments {

  static final String SERVER_CARD_SCHEMA =
      "https://static.modelcontextprotocol.io/schemas/v1/server-card.schema.json";
  static final String SKILL_ID = "settlement-date-answers";
  static final String A2A_PATH = "/a2a";
  static final String A2A_VERSION = "1.0";
  private static final String MCP_PATH = "/mcp";
  private static final List<String> MODES = List.of("text/plain", "application/json");
  private static final String AUTH_NOTE =
      "Optional. Without it, the six read tools answer at the anonymous limit (100 calls a day"
          + " per IPv4 /24 or IPv6 /48) with no receipt. With a Zeitwide API key (Bearer zw_live_…)"
          + " calls get the key's limits and a signed receipt.";

  private final DiscoveryProperties properties;
  private final String mcpName;
  private final String mcpVersion;
  private final List<String> protocolVersions;

  DiscoveryDocuments(
      DiscoveryProperties properties,
      String mcpName,
      String mcpVersion,
      List<String> protocolVersions) {
    this.properties = properties;
    this.mcpName = mcpName;
    this.mcpVersion = mcpVersion;
    this.protocolVersions = List.copyOf(protocolVersions);
  }

  Map<String, Object> serverCard() {
    return object(
        "$schema", SERVER_CARD_SCHEMA,
        "name", mcpName,
        "version", mcpVersion,
        "description", properties.description(),
        "title", properties.title(),
        "websiteUrl", properties.site("/"),
        "repository",
            object(
                "url", properties.repositoryUrl().toString(),
                "source", "github",
                "id", properties.repositoryId()),
        "icons", icons(),
        "remotes",
            List.of(
                object(
                    "type",
                    "streamable-http",
                    "url",
                    properties.api(MCP_PATH),
                    "headers",
                    List.of(
                        object(
                            "name",
                            "Authorization",
                            "description",
                            AUTH_NOTE,
                            "isRequired",
                            false,
                            "isSecret",
                            true,
                            "placeholder",
                            "Bearer zw_live_…")),
                    "supportedProtocolVersions",
                    protocolVersions)),
        "_meta",
            object(
                "com.zeitwide/auth",
                object(
                    "anonymous",
                        "is_business_day, next_business_day, add_business_days,"
                            + " explain_date, list_closures and get_changes need no credentials",
                    "apiKey", "Bearer zw_live_… from " + properties.site("/app/developers"),
                    "receipts",
                        "signed (Ed25519) only on authenticated calls; keys at "
                            + properties.site("/.well-known/zeitwide-keys.json"))));
  }

  Map<String, Object> agentCard() {
    return object(
        "name", properties.title(),
        "description",
            "Answers whether a date is a business day, and which business day is next or N days"
                + " on, for payment rails (Fedwire, FedACH, FedNow, T2, CHAPS) and public holiday"
                + " calendars. Every answer names its official sources and dataset release.",
        "supportedInterfaces",
            List.of(
                object(
                    "url",
                    properties.api(A2A_PATH),
                    "protocolBinding",
                    "JSONRPC",
                    "protocolVersion",
                    A2A_VERSION)),
        "provider", object("organization", properties.organization(), "url", properties.site("/")),
        "version", mcpVersion,
        "documentationUrl", properties.site("/docs/agents"),
        "capabilities",
            object("streaming", false, "pushNotifications", false, "extendedAgentCard", false),
        "securitySchemes",
            object(
                "apiKey",
                object(
                    "httpAuthSecurityScheme",
                    object(
                        "scheme", "Bearer",
                        "bearerFormat", "Zeitwide API key (zw_live_…)",
                        "description",
                            "Optional: read answers need no credentials; an API key adds the"
                                + " key's limits and a signed receipt to every answer."))),
        // Either no credentials at all, or an API key: read answers need none.
        "securityRequirements",
            List.of(
                object("schemes", object()),
                object("schemes", object("apiKey", object("list", List.of())))),
        "defaultInputModes", MODES,
        "defaultOutputModes", MODES,
        "skills",
            List.of(
                object(
                    "id",
                    SKILL_ID,
                    "name",
                    "Settlement date answers",
                    "description",
                    "Is a date a business day, the next business day, T+N, or the closures in"
                        + " a range, on one or more calendars (all must be open). Send a"
                        + " question as text, or JSON with operation, calendars, date, days,"
                        + " from and to. The reply is one message: a sentence citing the"
                        + " sources, and the full answer as JSON.",
                    "tags",
                    List.of(
                        "business-days", "settlement", "holidays", "payments", "calendars", "T+N"),
                    "examples",
                    List.of(
                        "Is 2026-12-28 a CHAPS business day?",
                        "T+2 from 2026-12-24 on US-FEDWIRE and GB-CHAPS",
                        "Next business day after 2026-07-03 on US-FEDACH",
                        "CHAPS closures from 2026-12-01 to 2026-12-31"),
                    "inputModes",
                    MODES,
                    "outputModes",
                    MODES)),
        "iconUrl", properties.site("/logo.png"));
  }

  Map<String, Object> aiCatalog() {
    String publisher = properties.siteUrl().getHost();
    return object(
        "specVersion", "1.0",
        "host",
            object(
                "displayName", properties.organization(),
                "identifier", publisher,
                "documentationUrl", properties.site("/docs")),
        "entries",
            List.of(
                object(
                    "identifier",
                    "urn:air:" + publisher + ":mcp:zeitwide",
                    "type",
                    "application/mcp-server-card+json",
                    "url",
                    properties.api("/v1/server-card")),
                object(
                    "identifier",
                    "urn:air:" + publisher + ":a2a:zeitwide",
                    "type",
                    "application/a2a-agent-card+json",
                    "url",
                    properties.site("/.well-known/agent-card.json")),
                entry(
                    publisher,
                    "api:openapi",
                    "Zeitwide REST API (OpenAPI 3.1)",
                    "application/vnd.oai.openapi+json",
                    properties.site("/openapi.json")),
                entry(
                    publisher,
                    "docs:llms-txt",
                    "llms.txt: an index of Zeitwide for agents",
                    "text/markdown",
                    properties.site("/llms.txt")),
                entry(
                    publisher,
                    "docs:llms-full",
                    "Every Zeitwide doc and the API reference",
                    "text/markdown",
                    properties.site("/docs/llms-full.txt")),
                entry(
                    publisher,
                    "keys:receipts",
                    "Ed25519 keys that verify answer receipts",
                    "application/json",
                    properties.site("/.well-known/zeitwide-keys.json"))));
  }

  private List<Map<String, Object>> icons() {
    return List.of(
        object(
            "src", properties.site("/logo.png"),
            "mimeType", "image/png",
            "sizes", List.of("215x215")),
        object(
            "src", properties.site("/icon.svg"),
            "mimeType", "image/svg+xml",
            "sizes", List.of("any")));
  }

  private static Map<String, Object> entry(
      String publisher, String name, String displayName, String type, String url) {
    return object(
        "identifier", "urn:air:" + publisher + ":" + name,
        "displayName", displayName,
        "type", type,
        "url", url);
  }

  /** An insertion-ordered JSON object from alternating keys and values. */
  private static Map<String, Object> object(Object... keysAndValues) {
    Map<String, Object> object = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      object.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return object;
  }
}
