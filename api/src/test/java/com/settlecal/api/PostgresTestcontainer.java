package com.settlecal.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A real PostgreSQL for tests, never H2. Flyway migrates it when the context starts. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

  // Same major version as the compose stack and Aiven.
  private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>(IMAGE);
  }
}
