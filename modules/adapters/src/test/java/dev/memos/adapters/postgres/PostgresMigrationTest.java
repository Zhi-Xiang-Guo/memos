package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgresMigrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "pgvector/pgvector:0.8.6-pg18@sha256:2ba9ca5f2e7daa0f0e7723cba1ee9167bab54efd3640516a44ac1a928dd67e7a")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("memos")
          .withUsername("memos")
          .withPassword("memos-test");

  @Test
  void migratesAndValidatesAnEmptyPgvectorDatabase() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
            .cleanDisabled(true)
            .validateMigrationNaming(true)
            .load();

    assertThat(flyway.migrate().success).isTrue();
    flyway.validate();

    try (var connection =
            DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var statement =
            connection.prepareStatement(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'");
        var result = statement.executeQuery()) {
      assertThat(result.next()).isTrue();
      assertThat(result.getString(1)).isEqualTo("0.8.6");
    }
  }
}
