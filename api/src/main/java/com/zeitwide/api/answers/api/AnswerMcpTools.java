package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.AnswerService;
import com.zeitwide.api.answers.app.Answered;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.answers.domain.DayAnswer;
import com.zeitwide.api.answers.domain.EntryVersion;
import com.zeitwide.api.answers.domain.Shifted;
import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.mcp.api.McpToolSupport;
import com.zeitwide.api.receipts.app.ReceiptService;
import com.zeitwide.api.web.RequestValidationException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** MCP adapters that use the same answer engine, API mappers and receipt envelope as REST. */
@Component
public final class AnswerMcpTools {

  private static final String CALENDAR_ID = AnswerDtos.CALENDAR_ID;
  private static final String COMBINE = AnswerDtos.COMBINE;
  private static final List<String> COMBINE_VALUES = List.of("all_open", "any_open");
  private static final int MAX_CLOSURE_RANGE_DAYS = 731;

  private final AnswerService answers;
  private final ReceiptService receipts;
  private final McpToolSupport tools;

  public AnswerMcpTools(AnswerService answers, ReceiptService receipts, McpToolSupport tools) {
    this.answers = answers;
    this.receipts = receipts;
    this.tools = tools;
  }

  public List<McpServerFeatures.SyncToolSpecification> specifications() {
    return List.of(
        tools.tool(
            "is_business_day",
            "Use when an agent needs to decide if a date is a business day across one or more calendars. Returns the REST check response, calendar explanations, sources, dataset release, revisions, and a verifiable receipt.",
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
            "Use when an agent needs the first business day strictly after a start date. Returns the REST next-day response with skipped days, sources, dataset release, revisions, and a verifiable receipt.",
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
            "Use when an agent needs to move forward or backward by business days. The start date is not counted, and zero returns the start date. Returns the REST add response with skipped days, sources, dataset release, revisions, and a verifiable receipt.",
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
            "Use when an agent needs the explanation for one calendar on one date. Returns the REST day response, including the deciding entry or weekend rule, source references, dataset release, revision, and a verifiable receipt.",
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
            "Use when an agent needs source-backed closures for one calendar over an inclusive date range. The range can cover at most 731 days. Returns the REST closure list with source references, dataset release, revision, and a verifiable receipt.",
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

  private McpSchema.CallToolResult isBusinessDay(Map<String, Object> supplied) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(supplied, "calendars", "date", "combine", "as_of");
          AnswerDtos.DayRequest request = dayRequest(values);
          Combine combine = Requests.combine(request.combine());
          Answered<DayAnswer> answered =
              answers.check(
                  Requests.calendars(request.calendars()),
                  combine,
                  request.date(),
                  Requests.asOf(request.asOf(), null));
          AnswerDtos.DayAnswer response = AnswerMapper.day(answered, combine);
          return response.withReceipt(
              AnswerReceipts.issue(
                  receipts,
                  new AnswerRequestEvidence(
                      "POST",
                      "/v1/business-days/check",
                      AnswerRequestEvidence.query(Map.of(), null),
                      request),
                  response,
                  answered));
        });
  }

  private McpSchema.CallToolResult nextBusinessDay(Map<String, Object> supplied) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(supplied, "calendars", "date", "combine", "as_of");
          AnswerDtos.DayRequest request = dayRequest(values);
          Combine combine = Requests.combine(request.combine());
          Answered<Shifted> answered =
              answers.next(
                  Requests.calendars(request.calendars()),
                  combine,
                  request.date(),
                  Requests.asOf(request.asOf(), null));
          AnswerDtos.Shifted response = AnswerMapper.shifted(answered, request.date(), 1, combine);
          return response.withReceipt(
              AnswerReceipts.issue(
                  receipts,
                  new AnswerRequestEvidence(
                      "POST",
                      "/v1/business-days/next",
                      AnswerRequestEvidence.query(Map.of(), null),
                      request),
                  response,
                  answered));
        });
  }

  private McpSchema.CallToolResult addBusinessDays(Map<String, Object> supplied) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(supplied, "calendars", "date", "days", "combine", "as_of");
          List<String> calendars = calendarIds(values);
          LocalDate date = LocalDate.parse(McpToolSupport.requiredString(values, "date"));
          int days = McpToolSupport.requiredInteger(values, "days");
          if (days < -AnswerDtos.MAX_DAYS || days > AnswerDtos.MAX_DAYS) {
            throw new IllegalArgumentException("days outside supported range");
          }
          String combineValue = combine(values);
          OffsetDateTime asOf = asOf(values);
          AnswerDtos.AddRequest request =
              new AnswerDtos.AddRequest(calendars, date, days, combineValue, asOf);
          Combine combine = Requests.combine(request.combine());
          Answered<Shifted> answered =
              answers.add(
                  Requests.calendars(request.calendars()),
                  combine,
                  request.date(),
                  request.days(),
                  Requests.asOf(request.asOf(), null));
          AnswerDtos.Shifted response =
              AnswerMapper.shifted(answered, request.date(), request.days(), combine);
          return response.withReceipt(
              AnswerReceipts.issue(
                  receipts,
                  new AnswerRequestEvidence(
                      "POST",
                      "/v1/business-days/add",
                      AnswerRequestEvidence.query(Map.of(), null),
                      request),
                  response,
                  answered));
        });
  }

  private McpSchema.CallToolResult explainDate(Map<String, Object> supplied) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(supplied, "calendar_id", "date", "as_of");
          CalendarId calendar =
              new CalendarId(McpToolSupport.requiredString(values, "calendar_id"));
          LocalDate date = LocalDate.parse(McpToolSupport.requiredString(values, "date"));
          OffsetDateTime asOf = asOf(values);
          Answered<DayAnswer> answered =
              answers.check(List.of(calendar), Combine.ALL_OPEN, date, Requests.asOf(asOf));
          AnswerDtos.DayAnswer response = AnswerMapper.day(answered, Combine.ALL_OPEN);
          return response.withReceipt(
              AnswerReceipts.issue(
                  receipts,
                  new AnswerRequestEvidence(
                      "GET",
                      "/v1/calendars/" + calendar.value() + "/days/" + date,
                      AnswerRequestEvidence.query(Map.of(), asOf),
                      null),
                  response,
                  answered));
        });
  }

  private McpSchema.CallToolResult listClosures(Map<String, Object> supplied) {
    return tools.invoke(
        () -> {
          Map<String, Object> values =
              McpToolSupport.arguments(supplied, "calendar_id", "from", "to", "as_of");
          CalendarId calendar =
              new CalendarId(McpToolSupport.requiredString(values, "calendar_id"));
          LocalDate from = LocalDate.parse(McpToolSupport.requiredString(values, "from"));
          LocalDate to = LocalDate.parse(McpToolSupport.requiredString(values, "to"));
          if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_CLOSURE_RANGE_DAYS) {
            throw new RequestValidationException("The closure range is invalid or too large");
          }
          OffsetDateTime asOf = asOf(values);
          Answered<List<EntryVersion>> answered =
              answers.closures(calendar, from, to, Requests.asOf(asOf));
          AnswerDtos.ClosureList response =
              AnswerMapper.closures(answered, calendar.value(), from, to);
          return response.withReceipt(
              AnswerReceipts.issue(
                  receipts,
                  new AnswerRequestEvidence(
                      "GET",
                      "/v1/calendars/" + calendar.value() + "/closures",
                      AnswerRequestEvidence.query(Map.of("from", from, "to", to), asOf),
                      null),
                  response,
                  answered));
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
