package com.zeitwide.api.mcp.app;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("zeitwide.mcp")
public record McpProperties(@NotBlank String resourceUrl) {

  public McpProperties {
    Objects.requireNonNull(resourceUrl, "resourceUrl");
    URI resource = URI.create(resourceUrl);
    if (!resource.isAbsolute()
        || !(resource.getScheme().equals("https") || resource.getScheme().equals("http"))
        || resource.getHost() == null
        || resource.getRawUserInfo() != null
        || resource.getRawQuery() != null
        || resource.getRawFragment() != null
        || !"/mcp".equals(resource.getPath())) {
      throw new IllegalArgumentException("MCP resource URL must be an absolute /mcp URL");
    }
  }

  public String protectedResourceMetadataUrl() {
    URI resource = URI.create(resourceUrl);
    return resource.getScheme()
        + "://"
        + resource.getRawAuthority()
        + "/.well-known/oauth-protected-resource/mcp";
  }
}
