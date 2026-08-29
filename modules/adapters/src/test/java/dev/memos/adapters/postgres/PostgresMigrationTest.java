package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            .defaultSchema("public")
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

    assertConfigurableEmbeddingProjection();
  }

  private static void assertConfigurableEmbeddingProjection() throws Exception {
    try (var connection =
        DriverManager.getConnection(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
      try (var statement =
              connection.prepareStatement(
                  """
                  SELECT format_type(attribute.atttypid, attribute.atttypmod)
                    FROM pg_attribute attribute
                    JOIN pg_class relation ON relation.oid = attribute.attrelid
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                   WHERE namespace.nspname = 'memos'
                     AND relation.relname = 'memory_search_projection'
                     AND attribute.attname = 'embedding'
                  """);
          var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("vector");
      }

      try (var statement =
              connection.prepareStatement(
                  """
                  SELECT indexdef
                    FROM pg_indexes
                   WHERE schemaname = 'memos'
                     AND indexname = 'memory_search_projection_embedding_hnsw_idx'
                  """);
          var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1))
            .containsIgnoringCase("USING hnsw")
            .contains("vector(1024)")
            .contains("embedding_dimensions = 1024");
      }

      try (var statement = connection.createStatement()) {
        statement.execute(
            """
            CREATE TEMP TABLE projection_embedding_contract
              (LIKE memos.memory_search_projection INCLUDING DEFAULTS INCLUDING GENERATED
               INCLUDING CONSTRAINTS)
            """);
      }
      insertProjectionContractRow(connection, 64, vector(64));
      insertProjectionContractRow(connection, 1_024, vector(1_024));
      assertThatThrownBy(() -> insertProjectionContractRow(connection, 1_024, vector(3)))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("memory_search_projection_embedding_ck");

      try (var statement =
              connection.prepareStatement("SELECT count(*) FROM projection_embedding_contract");
          var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(2);
      }
    }
  }

  private static void insertProjectionContractRow(
      java.sql.Connection connection, int dimensions, String vector) throws Exception {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO projection_embedding_contract (
                tenant_id, user_id, agent_id, memory_id, version_id, memory_type,
                subject_kind, predicate, truth_status, normalized_content, recorded_at,
                source_event_ids, embedding_model_version, embedding_dimensions, embedding,
                projection_policy_version, transition_id, transition_sequence, projected_at
            ) VALUES (
                'tenant-a', 'user-a', 'agent-a', gen_random_uuid(), gen_random_uuid(), 'SEMANTIC',
                'USER', 'preference.editor.theme', 'CURRENT', 'fixture', clock_timestamp(),
                ARRAY[gen_random_uuid()], 'embedding-v1', ?, ?::vector, 'projection-v1',
                gen_random_uuid(), 1, clock_timestamp()
            )
            """)) {
      statement.setInt(1, dimensions);
      statement.setString(2, vector);
      statement.executeUpdate();
    }
  }

  private static String vector(int dimensions) {
    return "[" + "0,".repeat(dimensions - 1) + "0]";
  }
}
