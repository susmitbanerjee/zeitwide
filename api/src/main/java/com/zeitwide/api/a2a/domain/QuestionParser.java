package com.zeitwide.api.a2a.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a question out of a message: plain text such as "T+2 from 2026-12-24 on US-FEDWIRE and
 * GB-CHAPS", or JSON with the MCP tools' argument names. It only reads; every date it returns comes
 * from the message, and every answer comes from the API.
 *
 * <p>Calendars are named by ID (GB-CHAPS) or by the part after the region (CHAPS), when that is
 * unique in the release. Dates must be ISO 8601 (2026-12-28), so nothing is guessed about day and
 * month order.
 */
public final class QuestionParser {

  public static final int MAX_TEXT_LENGTH = 2_000;
  static final int MAX_CALENDARS = 10;
  static final int MAX_DAYS = 1_000;

  private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
  private static final Pattern T_PLUS = Pattern.compile("\\bT\\s*([+-])\\s*(\\d{1,4})\\b");
  private static final Pattern N_DAYS =
      Pattern.compile(
          "\\b(\\d{1,4})\\s+(?:business|working|banking|settlement)\\s+days?\\s+(after|from|before)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern NEXT =
      Pattern.compile(
          "\\bnext\\s+(?:business|working|banking|settlement)\\s+day\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CLOSURES =
      Pattern.compile("\\b(?:closures?|closed\\s+days|holidays)\\b", Pattern.CASE_INSENSITIVE);
  // A two-letter name (DE) matches only in capitals, so "de" or "us" in a sentence is not one.
  private static final int CASE_SENSITIVE_UP_TO = 2;

  private final Map<String, String> names;
  private final Set<String> ids;

  /** {@code calendarIds}: every calendar in the release, such as GB-CHAPS and US-FEDWIRE. */
  public QuestionParser(Set<String> calendarIds) {
    this.ids = Set.copyOf(calendarIds);
    this.names = names(calendarIds);
  }

  public Question parseText(String text) {
    if (text == null || text.isBlank()) {
      throw new UnreadableQuestionException("The message has no text to read a question from.");
    }
    if (text.length() > MAX_TEXT_LENGTH) {
      throw new UnreadableQuestionException(
          "The question is longer than " + MAX_TEXT_LENGTH + " characters.");
    }
    List<LocalDate> dates = dates(text);
    List<String> calendars = calendars(text);
    if (dates.isEmpty()) {
      throw new UnreadableQuestionException(
          "Give the date as YYYY-MM-DD, for example \"Is 2026-12-28 a CHAPS business day?\".");
    }
    Matcher tPlus = T_PLUS.matcher(text);
    Matcher nDays = N_DAYS.matcher(text);
    if (tPlus.find()) {
      int days = days(tPlus.group(2));
      return new Question.Add(calendars, only(dates), "-".equals(tPlus.group(1)) ? -days : days);
    }
    if (nDays.find()) {
      int days = days(nDays.group(1));
      boolean back = "before".equalsIgnoreCase(nDays.group(2));
      return new Question.Add(calendars, only(dates), back ? -days : days);
    }
    if (NEXT.matcher(text).find()) {
      return new Question.Next(calendars, only(dates));
    }
    if (CLOSURES.matcher(text).find() || dates.size() == 2) {
      if (dates.size() != 2) {
        throw new UnreadableQuestionException(
            "For closures, give a first and a last date, for example \"CHAPS closures from"
                + " 2026-12-01 to 2026-12-31\".");
      }
      return new Question.Closures(one(calendars), dates.get(0), dates.get(1));
    }
    return new Question.Check(calendars, only(dates));
  }

  /**
   * Reads JSON shaped like the MCP tools' arguments: {@code operation} (is_business_day,
   * next_business_day, add_business_days or list_closures) plus {@code calendars}, the start {@code
   * date}, {@code days}, or {@code calendar_id}, {@code from} and {@code to}.
   */
  public Question parseData(Map<?, ?> fields) {
    Object operation = fields.get("operation");
    if (!(operation instanceof String name)) {
      throw new UnreadableQuestionException(
          "JSON questions need an operation: is_business_day, next_business_day,"
              + " add_business_days or list_closures.");
    }
    return switch (name) {
      case "is_business_day" -> new Question.Check(calendarList(fields), date(fields, "date"));
      case "next_business_day" -> new Question.Next(calendarList(fields), date(fields, "date"));
      case "add_business_days" ->
          new Question.Add(
              calendarList(fields), date(fields, "date"), signedDays(fields.get("days")));
      case "list_closures" ->
          new Question.Closures(calendarId(fields), date(fields, "from"), date(fields, "to"));
      default ->
          throw new UnreadableQuestionException("Unknown operation \"" + truncated(name) + "\".");
    };
  }

  private List<String> calendars(String text) {
    Set<String> found = new LinkedHashSet<>();
    Matcher token = TOKEN.matcher(text);
    while (token.find()) {
      String word = token.group();
      String key = word.length() <= CASE_SENSITIVE_UP_TO ? word : word.toUpperCase(Locale.ROOT);
      String id = names.get(key);
      if (id != null) {
        found.add(id);
      }
    }
    if (found.isEmpty()) {
      throw new UnreadableQuestionException(
          "Name at least one calendar, such as " + String.join(", ", sorted(ids)) + ".");
    }
    return checkedCount(new ArrayList<>(found));
  }

  private List<String> calendarList(Map<?, ?> data) {
    if (!(data.get("calendars") instanceof List<?> list) || list.isEmpty()) {
      throw new UnreadableQuestionException("calendars must be a non-empty list of calendar IDs.");
    }
    Set<String> found = new LinkedHashSet<>();
    for (Object item : list) {
      found.add(knownId(item));
    }
    return checkedCount(new ArrayList<>(found));
  }

  private String calendarId(Map<?, ?> data) {
    return knownId(data.get("calendar_id"));
  }

  private String knownId(Object value) {
    if (value instanceof String id && ids.contains(id)) {
      return id;
    }
    throw new UnreadableQuestionException(
        "Unknown calendar; the calendars are " + String.join(", ", sorted(ids)) + ".");
  }

  private static List<String> checkedCount(List<String> calendars) {
    if (calendars.size() > MAX_CALENDARS) {
      throw new UnreadableQuestionException("Ask about at most " + MAX_CALENDARS + " calendars.");
    }
    return calendars;
  }

  private static String one(List<String> calendars) {
    if (calendars.size() != 1) {
      throw new UnreadableQuestionException("Closures are listed for one calendar at a time.");
    }
    return calendars.getFirst();
  }

  private static List<LocalDate> dates(String text) {
    List<LocalDate> dates = new ArrayList<>();
    Matcher match = ISO_DATE.matcher(text);
    while (match.find()) {
      dates.add(parseDate(match.group(1)));
    }
    return dates;
  }

  private static LocalDate only(List<LocalDate> dates) {
    if (dates.size() != 1) {
      throw new UnreadableQuestionException("Give exactly one date for this question.");
    }
    return dates.getFirst();
  }

  private static LocalDate date(Map<?, ?> data, String field) {
    if (data.get(field) instanceof String value) {
      return parseDate(value);
    }
    throw new UnreadableQuestionException(field + " must be a date as YYYY-MM-DD.");
  }

  private static LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException invalid) {
      throw new UnreadableQuestionException(truncated(value) + " is not a valid date.");
    }
  }

  private static int days(String digits) {
    int days = Integer.parseInt(digits);
    if (days > MAX_DAYS) {
      throw new UnreadableQuestionException("Add at most " + MAX_DAYS + " business days.");
    }
    return days;
  }

  private static int signedDays(Object value) {
    if (value instanceof Integer days && Math.abs(days) <= MAX_DAYS) {
      return days;
    }
    throw new UnreadableQuestionException(
        "days must be a whole number from -" + MAX_DAYS + " to " + MAX_DAYS + ".");
  }

  /** Each ID, and the part after its region when only one calendar has it (CHAPS, FEDWIRE). */
  private static Map<String, String> names(Set<String> ids) {
    Map<String, String> names = new HashMap<>();
    Map<String, Integer> suffixCounts = new HashMap<>();
    for (String id : ids) {
      int dash = id.indexOf('-');
      if (dash > 0) {
        suffixCounts.merge(id.substring(dash + 1), 1, Integer::sum);
      }
    }
    for (String id : ids) {
      names.put(id, id);
      int dash = id.indexOf('-');
      if (dash > 0 && suffixCounts.get(id.substring(dash + 1)) == 1) {
        names.putIfAbsent(id.substring(dash + 1), id);
      }
    }
    return Map.copyOf(names);
  }

  private static List<String> sorted(Set<String> ids) {
    return ids.stream().sorted().toList();
  }

  private static String truncated(String value) {
    return value.length() > 40 ? value.substring(0, 40) + "…" : value;
  }
}
