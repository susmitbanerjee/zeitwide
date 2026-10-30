package com.zeitwide.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeitwide.api.PostgresJdbcTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks what the pipeline and backup logins can and can't do. Roles are cluster-wide but created
 * inside the test transaction, so they roll back with it.
 */
@PostgresJdbcTest
class RoleGrantsTest {

  private static final String PIPELINE = "zeitwide_pipeline";
  private static final String BACKUP = "zeitwide_backup";
  private static final String OUTSIDER = "zeitwide_outsider";

  private static final String PUBLISH =
      """
      SELECT publish_entry_version('US-FEDWIRE', DATE '2026-11-26', false, 'Thanksgiving',
                                   'published', 1, TIMESTAMPTZ '2026-01-01Z')
      """;

  @Autowired private JdbcClient jdbc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void createCalendarAndSource() {
    jdbcTemplate.execute(
        """
        INSERT INTO calendar (id, name, kind, timezone, weekend_rule)
            VALUES ('US-FEDWIRE', 'Fedwire Funds Service', 'rail', 'America/New_York', '{}');
        INSERT INTO source (slug, publisher, url, parser_name, schedule, role)
            VALUES ('fed-holidays', 'Federal Reserve', 'https://www.frbservices.org/',
                    'fed_holidays', 'daily', 'primary');
        """);
  }

  @Test
  void shouldSkipLoginsThatDoNotExist() {
    assertThat(applyGrants()).isEmpty();
  }

  @Test
  void shouldGrantToLoginsThatExist() {
    createRoles(PIPELINE, BACKUP);

    assertThat(applyGrants()).containsExactly(PIPELINE, BACKUP);
  }

  @Test
  void shouldLetPipelinePublishThroughFunction() {
    createRoles(PIPELINE);
    applyGrants();

    asRole(PIPELINE, PUBLISH);

    assertThat(jdbc.sql("SELECT count(*) FROM calendar_entry").query(Long.class).single())
        .isEqualTo(1L);
  }

  @Test
  void shouldLetPipelineStoreSnapshots() {
    createRoles(PIPELINE);
    applyGrants();

    asRole(
        PIPELINE,
        """
        INSERT INTO source_snapshot (source_id, fetched_at, sha256, object_key, http_status, byte_size)
            SELECT id, now(), sha256('page'::bytea), 'fed-holidays/abc', 200, 4 FROM source
        """);

    assertThat(jdbc.sql("SELECT count(*) FROM source_snapshot").query(Long.class).single())
        .isEqualTo(1L);
  }

  @Test
  void shouldLetPipelineRunCleanup() {
    createRoles(PIPELINE);
    applyGrants();

    assertThatCode(() -> asRole(PIPELINE, "SELECT * FROM cleanup_old_rows()"))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldStopPipelineUpdatingEntriesDirectly() {
    createRoles(PIPELINE);
    applyGrants();
    jdbcTemplate.execute(PUBLISH);

    assertThatThrownBy(() -> asRole(PIPELINE, "UPDATE calendar_entry SET reason = 'Edited'"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void shouldStopPipelineReadingApiKeys() {
    createRoles(PIPELINE);
    applyGrants();

    assertThatThrownBy(() -> asRole(PIPELINE, "SELECT prefix FROM api_key"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void shouldStopPipelineDeletingWebhookDeliveries() {
    createRoles(PIPELINE);
    applyGrants();

    assertThatThrownBy(() -> asRole(PIPELINE, "DELETE FROM webhook_delivery"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void shouldStopOtherRolesCallingPublish() {
    createRoles(OUTSIDER);

    assertThatThrownBy(() -> asRole(OUTSIDER, PUBLISH))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void shouldLetBackupReadEveryTable() {
    createRoles(BACKUP);
    applyGrants();

    assertThatCode(
            () ->
                asRole(
                    BACKUP,
                    "SELECT prefix FROM api_key; SELECT version FROM flyway_schema_history"))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldStopBackupWriting() {
    createRoles(BACKUP);
    applyGrants();

    assertThatThrownBy(() -> asRole(BACKUP, "DELETE FROM calendar"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  private void createRoles(String... roles) {
    for (String role : roles) {
      jdbcTemplate.execute("CREATE ROLE " + role + " NOLOGIN");
    }
  }

  private List<String> applyGrants() {
    return jdbc.sql("SELECT apply_role_grants()").query(String.class).list();
  }

  /** Runs SQL as another role inside a savepoint, so a failure rolls back only this part. */
  private void asRole(String role, String sql) {
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    nested.executeWithoutResult(
        status -> {
          jdbcTemplate.execute("SET LOCAL ROLE " + role);
          jdbcTemplate.execute(sql);
          jdbcTemplate.execute("RESET ROLE");
        });
  }
}
