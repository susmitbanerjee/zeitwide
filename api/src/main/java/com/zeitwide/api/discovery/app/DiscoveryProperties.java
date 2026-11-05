package com.zeitwide.api.discovery.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * What the discovery documents say about Zeitwide. The MCP name and version come from the MCP
 * server's own settings ({@code spring.ai.mcp.server}), so the server card always matches what the
 * server reports when a client connects.
 *
 * @param siteUrl the public website, where llms.txt, the docs and the AI catalog live
 * @param apiUrl the API's public origin, where /mcp and /a2a answer
 * @param description at most 100 characters: the server card's limit
 * @param repositoryId GitHub's numeric ID for the repository, which survives renames
 */
@Validated
@ConfigurationProperties("zeitwide.discovery")
public record DiscoveryProperties(
    @NotNull URI siteUrl,
    @NotNull URI apiUrl,
    @NotBlank @Size(max = 100) String title,
    @NotBlank @Size(max = 100) String description,
    @NotBlank String organization,
    @NotNull URI repositoryUrl,
    @NotBlank String repositoryId) {

  /** An absolute URL on the website. */
  public String site(String path) {
    return trimmed(siteUrl) + path;
  }

  /** An absolute URL on the API. */
  public String api(String path) {
    return trimmed(apiUrl) + path;
  }

  private static String trimmed(URI origin) {
    String value = origin.toString();
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
