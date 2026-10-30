package com.zeitwide.api.changes.app;

import com.zeitwide.api.calendars.domain.CalendarId;
import com.zeitwide.api.changes.domain.ChangeCursor;
import com.zeitwide.api.changes.domain.ChangeEvent;
import com.zeitwide.api.changes.domain.ChangeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangeFeedService {

  private static final int MAX_PAGE_SIZE = 100;
  private final ChangeRepository changes;

  public ChangeFeedService(ChangeRepository changes) {
    this.changes = changes;
  }

  @Transactional(readOnly = true)
  public ChangeFeedPage list(
      List<String> calendarValues,
      Instant since,
      List<String> typeValues,
      String cursorValue,
      int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
    }
    List<CalendarId> calendars =
        calendarValues.stream().distinct().sorted().map(CalendarId::new).toList();
    List<ChangeType> types;
    try {
      types = typeValues.stream().distinct().map(ChangeType::fromCode).sorted().toList();
    } catch (IllegalArgumentException invalidType) {
      throw new InvalidChangeTypeException();
    }
    String queryHash = queryHash(since, calendars, types);
    ChangeCursor cursor =
        cursorValue == null
            ? new ChangeCursor(since, 0, queryHash)
            : decode(cursorValue, queryHash);
    if (cursor.publishedAt().isBefore(since)) {
      throw new InvalidChangeCursorException();
    }
    List<ChangeEvent> found = changes.page(since, cursor, calendars, types, limit + 1);
    boolean hasMore = found.size() > limit;
    List<ChangeEvent> page = hasMore ? found.subList(0, limit) : found;
    String next = hasMore ? encode(page.getLast(), queryHash) : null;
    return new ChangeFeedPage(page, next, since, limit);
  }

  private static String encode(ChangeEvent event, String queryHash) {
    String value = event.publishedAt() + "\n" + event.id() + "\n" + queryHash;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static ChangeCursor decode(String encoded, String expectedQueryHash) {
    try {
      String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      String[] fields = value.split("\\n", -1);
      if (fields.length != 3 || !fields[2].equals(expectedQueryHash)) {
        throw new InvalidChangeCursorException();
      }
      long id = Long.parseLong(fields[1]);
      if (id < 1) {
        throw new InvalidChangeCursorException();
      }
      return new ChangeCursor(Instant.parse(fields[0]), id, fields[2]);
    } catch (IllegalArgumentException invalid) {
      if (invalid instanceof InvalidChangeCursorException cursorException) {
        throw cursorException;
      }
      throw new InvalidChangeCursorException();
    }
  }

  private static String queryHash(
      Instant since, List<CalendarId> calendars, List<ChangeType> types) {
    String query =
        since
            + "\n"
            + String.join(",", calendars.stream().map(CalendarId::value).toList())
            + "\n"
            + String.join(",", types.stream().map(ChangeType::code).toList());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("The JDK does not provide SHA-256", impossible);
    }
  }
}
