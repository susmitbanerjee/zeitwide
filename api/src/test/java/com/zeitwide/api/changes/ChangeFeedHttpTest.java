package com.zeitwide.api.changes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.TestApiKeys;
import com.zeitwide.api.answers.GoldenDatabase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class ChangeFeedHttpTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private MockMvc mvc;

  private long firstId;
  private long secondId;
  private OffsetDateTime published;
  private String since;

  private String apiKey;

  @BeforeEach
  void writeEvents() {
    GoldenDatabase.load(jdbc);
    apiKey = TestApiKeys.create(jdbc);
    Instant now = Instant.now();
    published = now.atOffset(ZoneOffset.UTC);
    since = now.minusSeconds(1).toString();
    long entry =
        jdbc.sql(
                "SELECT id FROM calendar_entry WHERE calendar_id = 'GB-CHAPS' "
                    + "AND entry_date = DATE '2022-09-19' ORDER BY id DESC LIMIT 1")
            .query(Long.class)
            .single();
    firstId = insert("closure_added", entry, "{\"open\":false}");
    secondId = insert("closure_changed", entry, "{\"open\":true}");
  }

  @Test
  void shouldUseStableKeysetPaginationWithCalendarAndTypeFilters() throws Exception {
    var first =
        mvc.perform(
                get("/v1/changes")
                    .header("Authorization", TestApiKeys.bearer(apiKey))
                    .param("calendars", "GB-CHAPS")
                    .param("since", since)
                    .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes.length()").value(1))
            .andExpect(jsonPath("$.changes[0].id").value(firstId))
            .andExpect(jsonPath("$.changes[0].type").value("closure_added"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(first)
            .path("next_cursor")
            .asText();

    mvc.perform(
            get("/v1/changes")
                .header("Authorization", TestApiKeys.bearer(apiKey))
                .param("calendars", "GB-CHAPS")
                .param("since", since)
                .param("limit", "1")
                .param("cursor", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].id").value(secondId))
        .andExpect(jsonPath("$.changes[0].type").value("closure_changed"))
        .andExpect(jsonPath("$.next_cursor").doesNotExist());

    mvc.perform(
            get("/v1/changes")
                .header("Authorization", TestApiKeys.bearer(apiKey))
                .param("since", since)
                .param("types", "closure_added"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.changes[*].type").value(org.hamcrest.Matchers.contains("closure_added")));
  }

  @Test
  void shouldRejectCursorReusedWithDifferentFilters() throws Exception {
    String cursor =
        mvc.perform(
                get("/v1/changes")
                    .header("Authorization", TestApiKeys.bearer(apiKey))
                    .param("calendars", "GB-CHAPS")
                    .param("since", since)
                    .param("limit", "1"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String value =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(cursor)
            .path("next_cursor")
            .asText();

    mvc.perform(
            get("/v1/changes")
                .header("Authorization", TestApiKeys.bearer(apiKey))
                .param("calendars", "US-FEDWIRE")
                .param("since", since)
                .param("limit", "1")
                .param("cursor", value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  private long insert(String type, long entryId, String afterState) {
    return jdbc.sql(
            """
            INSERT INTO change_event
                (change_type, calendar_id, entry_date, entry_id, after_state, notice_days, published_at)
            VALUES (:type, 'GB-CHAPS', DATE '2022-09-19', :entry, CAST(:after AS jsonb), -9, :published)
            RETURNING id
            """)
        .param("type", type)
        .param("entry", entryId)
        .param("after", afterState)
        .param("published", published)
        .query(Long.class)
        .single();
  }
}
