package com.zeitwide.api.mcp.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.mcp.api.McpToolSupport;
import com.zeitwide.api.ratelimits.api.RequestCaller;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI's streamable HTTP transport, plus the one thing it lacks: each request's caller, as the
 * rate limit filter resolved it, carried into the tool handlers so they can tell anonymous calls
 * (no receipt, per-network limit) from keyed ones.
 */
@Configuration(proxyBeanMethods = false)
class McpTransportConfiguration {

  @Bean
  WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
      ObjectMapper json, McpServerStreamableHttpProperties properties) {
    return WebMvcStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(json))
        .mcpEndpoint(properties.getMcpEndpoint())
        .keepAliveInterval(properties.getKeepAliveInterval())
        .disallowDelete(properties.isDisallowDelete())
        .contextExtractor(
            request ->
                RequestCaller.of(request.servletRequest())
                    .<McpTransportContext>map(
                        caller ->
                            McpTransportContext.create(
                                Map.of(McpToolSupport.CALLER_CONTEXT_KEY, caller)))
                    .orElse(McpTransportContext.EMPTY))
        .build();
  }
}
