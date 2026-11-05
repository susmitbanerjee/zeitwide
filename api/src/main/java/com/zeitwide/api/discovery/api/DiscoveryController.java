package com.zeitwide.api.discovery.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.discovery.app.DiscoveryProperties;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the discovery documents. Each is fixed for the life of the process, so it is serialized
 * once, with a strong ETag for conditional requests and an hour of public caching, as the server
 * card and agent card specs recommend.
 */
@RestController
@EnableConfigurationProperties(DiscoveryProperties.class)
class DiscoveryController {

  static final MediaType SERVER_CARD = MediaType.parseMediaType("application/mcp-server-card+json");
  static final MediaType AI_CATALOG = MediaType.parseMediaType("application/ai-catalog+json");
  private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

  private final Document serverCard;
  private final Document agentCard;
  private final Document aiCatalog;

  DiscoveryController(
      DiscoveryProperties properties,
      McpServerProperties mcp,
      WebMvcStreamableServerTransportProvider transport,
      ObjectMapper json)
      throws JsonProcessingException {
    DiscoveryDocuments documents =
        new DiscoveryDocuments(
            properties, mcp.getName(), mcp.getVersion(), transport.protocolVersions());
    this.serverCard = Document.of(json, documents.serverCard(), SERVER_CARD);
    this.agentCard = Document.of(json, documents.agentCard(), MediaType.APPLICATION_JSON);
    this.aiCatalog = Document.of(json, documents.aiCatalog(), AI_CATALOG);
  }

  /** The location this API's docs and AI catalog link to. */
  @GetMapping("/v1/server-card")
  ResponseEntity<byte[]> serverCard(
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    return serverCard.response(ifNoneMatch);
  }

  /** The location SEP-2127 reserves: the streamable HTTP endpoint plus /server-card. */
  @GetMapping("/mcp/server-card")
  ResponseEntity<byte[]> reservedServerCard(
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    return serverCard.response(ifNoneMatch);
  }

  @GetMapping("/.well-known/agent-card.json")
  ResponseEntity<byte[]> agentCard(
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    return agentCard.response(ifNoneMatch);
  }

  @GetMapping("/.well-known/ai-catalog.json")
  ResponseEntity<byte[]> aiCatalog(
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    return aiCatalog.response(ifNoneMatch);
  }

  private static final class Document {

    private final byte[] body;
    private final String etag;
    private final MediaType type;

    private Document(byte[] body, MediaType type) {
      this.body = body;
      this.etag = "\"" + HexFormat.of().formatHex(sha256(body)) + "\"";
      this.type = type;
    }

    static Document of(ObjectMapper json, Map<String, Object> value, MediaType type)
        throws JsonProcessingException {
      return new Document(json.writeValueAsBytes(value), type);
    }

    private static byte[] sha256(byte[] body) {
      try {
        return MessageDigest.getInstance("SHA-256").digest(body);
      } catch (NoSuchAlgorithmException missing) {
        throw new IllegalStateException("Every JVM provides SHA-256", missing);
      }
    }

    ResponseEntity<byte[]> response(String ifNoneMatch) {
      // If-None-Match may list several tags, or weak ones; a match on ours is a match.
      if (ifNoneMatch != null && (ifNoneMatch.contains(etag) || "*".equals(ifNoneMatch.trim()))) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
            .eTag(etag)
            .cacheControl(CACHE)
            .build();
      }
      return ResponseEntity.ok().contentType(type).eTag(etag).cacheControl(CACHE).body(body);
    }
  }
}
