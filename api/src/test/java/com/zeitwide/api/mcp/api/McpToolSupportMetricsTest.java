package com.zeitwide.api.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolSupportMetricsTest {

  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final McpToolSupport tools = new McpToolSupport(new ObjectMapper(), meters);

  @Test
  void shouldCountCallsByToolAndOutcome() {
    McpServerFeatures.SyncToolSpecification ok =
        tools.tool("echo", "Echo", Map.of(), List.of(), arguments -> tools.invoke(Map::of));
    McpServerFeatures.SyncToolSpecification failing =
        tools.tool(
            "broken",
            "Broken",
            Map.of(),
            List.of(),
            arguments -> McpToolSupport.error("validation_failed", "bad"));

    call(ok, "echo");
    call(ok, "echo");
    call(failing, "broken");

    assertThat(calls("echo", "ok")).isEqualTo(2);
    assertThat(calls("broken", "error")).isEqualTo(1);
  }

  private static McpSchema.CallToolResult call(
      McpServerFeatures.SyncToolSpecification tool, String name) {
    return tool.callHandler().apply(null, new McpSchema.CallToolRequest(name, Map.of()));
  }

  private double calls(String tool, String outcome) {
    return meters
        .get(McpToolSupport.TOOL_CALLS_METRIC)
        .tag("tool", tool)
        .tag("outcome", outcome)
        .counter()
        .count();
  }
}
