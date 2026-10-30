package com.zeitwide.api.mcp.api;

import com.zeitwide.api.auth.app.AuthProperties;
import com.zeitwide.api.mcp.app.McpProperties;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProtectedResourceMetadataController {

  private final McpProperties mcp;
  private final AuthProperties auth;

  public ProtectedResourceMetadataController(McpProperties mcp, AuthProperties auth) {
    this.mcp = mcp;
    this.auth = auth;
  }

  @GetMapping("/.well-known/oauth-protected-resource/mcp")
  ProtectedResourceMetadata metadata() {
    return new ProtectedResourceMetadata(
        mcp.resourceUrl(), List.of(auth.issuerUri()), List.of("header"));
  }

  public record ProtectedResourceMetadata(
      String resource, List<String> authorizationServers, List<String> bearerMethodsSupported) {}
}
