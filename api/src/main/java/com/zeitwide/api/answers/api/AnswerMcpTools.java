package com.zeitwide.api.answers.api;

import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.mcp.api.McpToolSupport;
import com.zeitwide.api.mcp.api.McpToolSupport.McpCall;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * MCP adapters that use the same answer engine, API mappers and receipt envelope as REST. Anyone
 * may call them; only callers with an API key or a signed-in user's token get a receipt.
 */
@Component
public final class AnswerMcpTools {

  private static final String CALENDAR_ID = AnswerDtos.CALENDAR_ID;
  private static final String COMBINE = AnswerDtos.COMBINE;
  private static final List<String> COMBINE_VALUES = List.of("all_open", "any_open");
  private static final String SIGNED =
      ", the dataset release, and a signed receipt when called with an API key.";

  private final AnswerResponses responses;
  private final McpToolSupport tools;

  AnswerMcpTools(AnswerResponses responses, McpToolSupport tools) {
    this.responses = responses;
    this.tools = tools;
  }

  public List<McpServerFeatures.SyncToolSpecification> specifications() {
    return List.of(
        tools.tool(
            "is_business_day",
            "Given calendar IDs (such as GB-CHAPS), a date and optional combine and as_of, returns whether the date is a business day with each calendar's deciding entry and official sources"
                + SIGNED,
            Map.of(
                "calendars",
                    McpToolSupport.array(
                        "Calendar IDs to check.",
                        McpToolSupport.string("Calendar ID", null, CALENDAR_ID, null, 64),
                        1,
                        AnswerDtos.MAX_CALENDARS,
                        true),
                "date", McpToolSupport.string("Date to check.", "date", null, null),
                "combine",
                    McpToolSupport.string(
                        "Optional combination rule; defaults to all_open.",
                        null,
                        null,
                        COMBINE_VALUES),
                "as_of",
                    McpToolSupport.string(
                        "Optional ISO-8601 offset date-time for historical evidence.",
                        "date-time",
                        null,
                        null)),
            List.of("calendars", "date"),
            this::isBusinessDay),
        tools.tool(
            "next_business_day",
            "Given calendar IDs, a start date and optional combine and as_of, returns the first business day strictly after the start with every skipped day's reason and official sources"
                + SIGNED,
            Map.of(
                "calendars",
                    McpToolSupport.array(
                        "Calendar IDs to check.",
                        McpToolSupport.string("Calendar ID", null, CALENDAR_ID, null, 64),
                        1,
                        AnswerDtos.MAX_CALENDARS,
                        true),
                "date", McpToolSupport.string("Start date.", "date", null, null),
                "combine",
                    McpToolSupport.string(
                        "Optional combination rule; defaults to all_open.",
                        null,
                        null,
                        COMBINE_VALUES),
                "as_of",
                    McpToolSupport.string(
                        "Optional ISO-8601 offset date-time for historical evidence.",
                        "date-time",
                        null,
                        null)),
            List.of("calendars", "date"),
            this::nextBusinessDay),
        tools.tool(
            "add_business_days",
            "Given calendar IDs, a start date (not counted), a signed day count from -1000 to 1000 and optional combine and as_of, returns the resulting business day with every skipped day's reason and official sources"
                + SIGNED,
            Map.of(
                "calendars",
                    McpToolSupport.array(
                        "Calendar IDs to check.",
                        McpToolSupport.string("Calendar ID", null, CALENDAR_ID, null, 64),
                        1,
                        AnswerDtos.MAX_CALENDARS,
                        true),
                "date", McpToolSupport.string("Start date.", "date", null, null),
                "days",
                    McpToolSupport.integer(
                        "Signed number of business days to add; limited to -1000 through 1000.",
                        -AnswerDtos.MAX_DAYS,
                        AnswerDtos.MAX_DAYS),
                "combine",
                    McpToolSupport.string(
                        "Optional combination rule; defaults to all_open.",
                        null,
                        null,
                        COMBINE_VALUES),
                "as_of",
                    McpToolSupport.string(
                        "Optional ISO-8601 offset date-time for historical evidence.",
                        "date-time",
                        null,
                        null)),
            List.of("calendars", "date", "days"),
            this::addBusinessDays),
        tools.tool(
            "explain_date",
            "Given one calendar ID, a date and an optional as_of, returns whether that calendar is open and why (the deciding holiday entry or weekend rule) with its official sources"
                + SIGNED,
            Map.of(
                "calendar_id", McpToolSupport.string("Calendar ID.", null, CALENDAR_ID, null, 64),
                "date", McpToolSupport.string("Date to explain.", "date", null, null),
                "as_of",
                    McpToolSupport.string(
                        "Optional ISO-8601 offset date-time for historical evidence.",
                        "date-time",
                        null,
                        null)),
            List.of("calendar_id", "date"),
            this::explainDate),
        tools.tool(
            "list_closures",
            "Given one calendar ID, inclusive from and to dates at most 731 days apart and an optional as_of, returns every closure in the range with its reason and official sources"
                + SIGNED,
            Map.of(
                "calendar_id", McpToolSupport.string("Calendar ID.", null, CALENDAR_ID, null, 64),
                "from", McpToolSupport.string("Inclusive first date.", "date", null, null),
                "to", McpToolSupport.string("Inclusive last date.", "date", null, null),
                "as_of",
                    McpToolSupport.string(
                        "Optional ISO-8601 offset date-time for historical evidence.",
                        "date-time",
                        null,
                        null)),
            List.of("calendar_id", "from", "to"),
            this::listClosures));
  }

  private McpSchema.CallToolResult isBusinessDay(McpCall call) {
    return tools.invoke(
        () ->
            responses.check(
                dayRequest(
                    McpToolSupport.arguments(
                        call.arguments(), "calendars", "date", "combine", "as_of")),
                call.signed()));
  }

  private McpSchema.CallToolResult nextBusinessDay(McpCall call) {
    return tools.invoke(
        () ->
            responses.next(
                dayRequest(
                    McpToolSupport.arguments(
                        call.arguments(), "calendars", "date", "combine", "as_of")),
                call.signed()));
  }

  private McpSchema.CallToolResult addBusinessDays(McpCall call) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(
                  call.arguments(), "calendars", "date", "days", "combine", "as_of");
          int days = McpToolSupport.requiredInteger(values, "days");
          if (days < -AnswerDtos.MAX_DAYS || days > AnswerDtos.MAX_DAYS) {
            throw new IllegalArgumentException("days outside supported range");
          }
          return responses.add(
              new AnswerDtos.AddRequest(
                  calendarIds(values),
                  LocalDate.parse(McpToolSupport.requiredString(values, "date")),
                  days,
                  combine(values),
                  asOf(values)),
              call.signed());
        });
  }

  private McpSchema.CallToolResult explainDate(McpCall call) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(call.arguments(), "calendar_id", "date", "as_of");
          return responses.explain(
              new CalendarId(McpToolSupport.requiredString(values, "calendar_id")),
              LocalDate.parse(McpToolSupport.requiredString(values, "date")),
              asOf(values),
              call.signed());
        });
  }

  private McpSchema.CallToolResult listClosures(McpCall call) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(call.arguments(), "calendar_id", "from", "to", "as_of");
          return responses.closures(
              new CalendarId(McpToolSupport.requiredString(values, "calendar_id")),
              LocalDate.parse(McpToolSupport.requiredString(values, "from")),
              LocalDate.parse(McpToolSupport.requiredString(values, "to")),
              asOf(values),
              call.signed());
        });
  }

  private static AnswerDtos.DayRequest dayRequest(Map<String, Object> values) {
    return new AnswerDtos.DayRequest(
        calendarIds(values),
        LocalDate.parse(McpToolSupport.requiredString(values, "date")),
        combine(values),
        asOf(values));
  }

  private static List<String> calendarIds(Map<String, Object> values) {
    return McpToolSupport.requiredStrings(
        values, "calendars", 1, AnswerDtos.MAX_CALENDARS, 64, CALENDAR_ID);
  }

  private static String combine(Map<String, Object> values) {
    String combine = McpToolSupport.optionalString(values, "combine");
    if (combine != null && !combine.matches(COMBINE)) {
      throw new IllegalArgumentException("Unknown combination rule");
    }
    return combine;
  }

  private static OffsetDateTime asOf(Map<String, Object> values) {
    String asOf = McpToolSupport.optionalString(values, "as_of");
    return asOf == null ? null : OffsetDateTime.parse(asOf);
  }
}
