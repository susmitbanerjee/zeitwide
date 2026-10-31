package com.zeitwide.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeitwide.api.PostgresTestcontainer;
import com.zeitwide.api.answers.GoldenDatabase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The admin review queue, source health and releases, against a real Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class ReviewApiHttpTest {

  private static final String REVIEWER = "kc-reviewer-subject-1";

  @Autowired private JdbcClient jdbc;
  @Autowired private MockMvc mvc;

  private String slug;
  private long candidateId;
  private LocalDate date;

  @BeforeEach
  void proposeAChange() {
    GoldenDatabase.load(jdbc);
    slug = "review-test-" + UUID.randomUUID().toString().substring(0, 8);
    // A date no other test proposes, far from the golden entries.
    date = LocalDate.of(2031, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3000));
    long sourceId =
        jdbc.sql(
                """
                INSERT INTO source (slug, publisher, url, parser_name, schedule, role)
                VALUES (:slug, 'GOV.UK', 'https://www.gov.uk/bank-holidays', 'gov-uk', 'daily',
                        'primary')
                RETURNING id
                """)
            .param("slug", slug)
            .query(Long.class)
            .single();
    long snapshotId =
        jdbc.sql(
                """
                INSERT INTO source_snapshot (source_id, fetched_at, sha256, object_key,
                                             http_status, content_type, byte_size)
                VALUES (:source, now(), sha256(convert_to(:slug, 'UTF8')), 'snapshots/x', 200,
                        'application/json', 2048)
                RETURNING id
                """)
            .param("source", sourceId)
            .param("slug", slug)
            .query(Long.class)
            .single();
    long observationId =
        jdbc.sql(
                """
                INSERT INTO observation (snapshot_id, calendar_id, entry_date, is_open, reason,
                                         parser_version)
                VALUES (:snapshot, 'GB-CHAPS', :date, false, 'Coronation bank holiday', 'v3')
                RETURNING id
                """)
            .param("snapshot", snapshotId)
            .param("date", date)
            .query(Long.class)
            .single();
    candidateId =
        jdbc.sql(
                """
                INSERT INTO candidate_change (calendar_id, entry_date, after_state, corroboration,
                                              source_count)
                VALUES ('GB-CHAPS', :date,
                        '{"is_open": false, "reason": "Coronation bank holiday"}'::jsonb,
                        'single_source', 1)
                RETURNING id
                """)
            .param("date", date)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO candidate_observation (candidate_id, observation_id) VALUES (:c, :o)")
        .param("c", candidateId)
        .param("o", observationId)
        .update();
    jdbc.sql(
            """
            INSERT INTO source_state (source_id, last_checked_at, consecutive_failures, last_error)
            VALUES (:source, now(), 2, 'HTTP 503')
            """)
        .param("source", sourceId)
        .update();
  }

  @Test
  void shouldListAndExplainAPendingCandidate() throws Exception {
    mvc.perform(get("/v1/admin/candidates").with(reviewer()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.candidates[?(@.id == " + candidateId + ")].calendar_id").value("GB-CHAPS"));

    mvc.perform(get("/v1/admin/candidates/{id}", candidateId).with(reviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidate.status").value("pending"))
        .andExpect(jsonPath("$.candidate.after.is_open").value(false))
        .andExpect(jsonPath("$.candidate.before").isEmpty())
        .andExpect(jsonPath("$.candidate.urgent").value(false))
        .andExpect(jsonPath("$.current").isEmpty())
        .andExpect(jsonPath("$.snapshots[0].source").value(slug))
        .andExpect(jsonPath("$.snapshots[0].reason").value("Coronation bank holiday"))
        .andExpect(jsonPath("$.snapshots[0].snapshot_sha256").isString())
        .andExpect(jsonPath("$.reviewer").isEmpty());
  }

  @Test
  void shouldRecordTheTokenSubjectAsReviewerAndDecideOnlyOnce() throws Exception {
    mvc.perform(decide("approve", "Matches the proclamation").with(reviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidate.status").value("approved"))
        .andExpect(jsonPath("$.reviewer").value(REVIEWER))
        .andExpect(jsonPath("$.review_reason").value("Matches the proclamation"));

    String stored =
        jdbc.sql("SELECT reviewer_subject FROM candidate_change WHERE id = :id")
            .param("id", candidateId)
            .query(String.class)
            .single();
    assertThat(stored).isEqualTo(REVIEWER);

    mvc.perform(decide("reject", "Second thoughts").with(reviewer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("candidate_not_pending"));
  }

  @Test
  void shouldRejectBadDecisionsAndUnknownCandidates() throws Exception {
    mvc.perform(decide("maybe", "Unsure").with(reviewer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
    mvc.perform(
            post("/v1/admin/candidates/{id}/decision", Long.MAX_VALUE)
                .with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"reject\",\"reason\":\"No such\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
  }

  @Test
  void shouldAdmitOnlyReviewers() throws Exception {
    mvc.perform(get("/v1/admin/candidates")).andExpect(status().isUnauthorized());
    mvc.perform(get("/v1/admin/candidates").with(jwt().jwt(token -> token.subject("someone"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("forbidden"));
    mvc.perform(get("/v1/admin/sources").with(jwt())).andExpect(status().isForbidden());
  }

  @Test
  void shouldReportSourceHealthAndReleases() throws Exception {
    mvc.perform(get("/v1/admin/sources").with(reviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sources[?(@.source == '" + slug + "')].health").value("failing"))
        .andExpect(
            jsonPath("$.sources[?(@.source == '" + slug + "')].consecutive_failures").value(2))
        .andExpect(
            jsonPath("$.sources[?(@.source == '" + slug + "')].last_snapshot_sha256").isNotEmpty());

    mvc.perform(get("/v1/admin/releases").param("limit", "5").with(reviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.releases[?(@.id == '2026.10.29.0')].export_sha256").exists());
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decide(
      String decision, String reason) {
    return post("/v1/admin/candidates/{id}/decision", candidateId)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"decision\":\"" + decision + "\",\"reason\":\"" + reason + "\"}");
  }

  private static RequestPostProcessor reviewer() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(REVIEWER)
                    .issuedAt(OffsetDateTime.now(ZoneOffset.UTC).toInstant().minusSeconds(5)))
        .authorities(new SimpleGrantedAuthority("ROLE_reviewer"));
  }
}
