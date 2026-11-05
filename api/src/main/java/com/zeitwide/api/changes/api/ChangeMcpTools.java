package com.zeitwide.api.changes.api;

import com.zeitwide.api.changes.app.ChangeFeedService;
import com.zeitwide.api.mcp.api.McpToolSupport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** MCP wrapper for the public keyset-paginated REST change feed. */
@Component
public final class ChangeMcpTools {

  private static final String CALENDAR_ID = ChangeController.CALENDAR_ID;
  private static final String CHANGE_TYPE = ChangeController.CHANGE_TYPE;
  private static final List<String> CHANGE_TYPES =
      List.of("closure_added", "closure_removed", "closure_changed", "basis_changed");
  private final ChangeFeedService changes;
  private final McpToolSupport tools;

  public ChangeMcpTools(ChangeFeedService changes, McpToolSupport tools) {
    this.changes = changes;
    this.tools = tools;
  }

  public List<McpServerFeatures.SyncToolSpecification> specifications() {
    return List.of(
        tools.tool(
            "get_changes",
            "Given a since instant and optional calendar IDs, change types, cursor and limit (1 to 100), returns the changes published since then with each date's before and after state and basis (published by an official source or projected), plus a keyset cursor for the next page; call explain_date for a date's sources.",
            Map.of(
                "calendars",
                    McpToolSupport.array(
                        "Optional calendar filters; omit or pass an empty list for all calendars.",
                        McpToolSupport.string("Calendar ID", null, CALENDAR_ID, null, 64),
                        10,
                        true),
                "since",
                    McpToolSupport.string(
                        "Required ISO-8601 offset date-time; return changes published at or after this instant.",
                        "date-time",
                        null,
                        null),
                "types",
                    McpToolSupport.array(
                        "Optional event type filters.",
                        McpToolSupport.string("Change type.", null, null, CHANGE_TYPES, 32),
                        4,
                        true),
                "cursor",
                    Map.of(
                        "type", "string",
                        "description", "Opaque cursor returned by the previous page.",
                        "maxLength", 256),
                "limit",
                    Map.of(
                        "type",
                        "integer",
                        "description",
                        "Page size from 1 to 100; defaults to 50.",
                        "minimum",
                        1,
                        "maximum",
                        100)),
            List.of("since"),
            this::getChanges));
  }

  private McpSchema.CallToolResult getChanges(McpToolSupport.McpCall call) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(
                  call.arguments(), "calendars", "since", "types", "cursor", "limit");
          List<String> calendars =
              McpToolSupport.optionalStrings(values, "calendars", 10, 64, CALENDAR_ID);
          List<String> types = McpToolSupport.optionalStrings(values, "types", 4, 32, CHANGE_TYPE);
          String cursor = McpToolSupport.optionalString(values, "cursor");
          if (cursor != null && cursor.length() > 256) {
            throw new IllegalArgumentException("Cursor exceeds the maximum length");
          }
          int limit = McpToolSupport.optionalInteger(values, "limit", 50);
          if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
          }
          OffsetDateTime since =
              OffsetDateTime.parse(McpToolSupport.requiredString(values, "since"));
          return ChangeDtos.page(changes.list(calendars, since.toInstant(), types, cursor, limit));
        });
  }
}
