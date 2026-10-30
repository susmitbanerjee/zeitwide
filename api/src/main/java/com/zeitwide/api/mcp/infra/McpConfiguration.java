package com.zeitwide.api.mcp.infra;

import com.zeitwide.api.answers.api.AnswerMcpTools;
import com.zeitwide.api.changes.api.ChangeMcpTools;
import com.zeitwide.api.mcp.app.McpProperties;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
class McpConfiguration {

  @Bean
  List<McpServerFeatures.SyncToolSpecification> zeitwideMcpTools(
      AnswerMcpTools answers, ChangeMcpTools changes) {
    List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>(answers.specifications());
    tools.addAll(changes.specifications());
    return List.copyOf(tools);
  }
}
