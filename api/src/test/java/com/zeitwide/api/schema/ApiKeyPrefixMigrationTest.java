package com.zeitwide.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V18 moves keys issued before the rename from sc_ to zw_, keeping their secrets. */
@Testcontainers
class ApiKeyPrefixMigrationTest {

  // Same major version as the compose stack and Aiven.
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.6-alpine");

  private static final String ACCOUNT = "00000000-0000-0000-0000-000000000001";
  private static final String SECRET_HASH = "ab".repeat(32);

  @Test
  void shouldMoveExistingKeysToTheZeitwidePrefix() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcClient jdbc = JdbcClient.create(dataSource);
    flyway(dataSource, "17").migrate();
    jdbc.sql("INSERT INTO account (id, name) VALUES (CAST(:id AS uuid), 'Acme')")
        .param("id", ACCOUNT)
        .update();
    jdbc.sql(
            """
            INSERT INTO api_key (account_id, name, prefix, secret_sha256)
            VALUES (CAST(:account AS uuid), 'old', 'sc_live_AbCd1234', decode(:hash, 'hex'))
            """)
        .param("account", ACCOUNT)
        .param("hash", SECRET_HASH)
        .update();

    flyway(dataSource, "latest").migrate();

    assertThat(
            jdbc.sql("SELECT prefix || ':' || encode(secret_sha256, 'hex') FROM api_key")
                .query(String.class)
                .single())
        .isEqualTo("zw_live_AbCd1234:" + SECRET_HASH);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO api_key (account_id, name, prefix, secret_sha256)
                        VALUES (CAST(:account AS uuid), 'new', 'sc_live_ZzZz9999',
                                decode(:hash, 'hex'))
                        """)
                    .param("account", ACCOUNT)
                    .param("hash", SECRET_HASH)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }
}
