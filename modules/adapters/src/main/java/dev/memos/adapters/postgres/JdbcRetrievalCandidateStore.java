package dev.memos.adapters.postgres;

import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.retrieval.CandidateSource;
import dev.memos.retrieval.CandidateStoreQuery;
import dev.memos.retrieval.ComponentCandidate;
import dev.memos.retrieval.ComponentSignal;
import dev.memos.retrieval.ProjectedMemory;
import dev.memos.retrieval.RetrievalCandidateStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent scoped candidate generators over the rebuildable PostgreSQL projection. */
public final class JdbcRetrievalCandidateStore implements RetrievalCandidateStore {
  private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1_024;
  private static final String COLUMNS =
      """
      projection.memory_id, projection.version_id, projection.memory_type,
      projection.subject_kind, projection.subject_label, projection.predicate,
      projection.truth_status, projection.normalized_content,
      projection.valid_time_start, projection.valid_time_end, projection.recorded_at,
      projection.source_event_ids, projection.projection_policy_version,
      projection.embedding_model_version, projection.transition_sequence,
      projection.projected_at
      """;
  private static final String VISIBILITY =
      """
               AND EXISTS (
                   SELECT 1 FROM memos.memory_lineage visible_lineage
                    WHERE visible_lineage.tenant_id = projection.tenant_id
                      AND visible_lineage.memory_id = projection.memory_id
                      AND visible_lineage.lifecycle_state = 'ACTIVE'
               )
               AND (?::text <> 'PRESENT'
                    OR projection.truth_status IN ('CURRENT', 'CONFLICTED'))
               AND (?::timestamptz IS NULL
                    OR ((projection.valid_time_start IS NULL
                         OR projection.valid_time_start <= ?::timestamptz)
                        AND (projection.valid_time_end IS NULL
                         OR projection.valid_time_end > ?::timestamptz)))
      """;

  private final JdbcTemplate jdbc;
  private final int embeddingDimensions;
  private final String vectorType;

  public JdbcRetrievalCandidateStore(JdbcTemplate jdbc) {
    this(jdbc, DEFAULT_EMBEDDING_DIMENSIONS);
  }

  public JdbcRetrievalCandidateStore(JdbcTemplate jdbc, int embeddingDimensions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    if (embeddingDimensions < 1 || embeddingDimensions > 2_000) {
      throw new IllegalArgumentException("embeddingDimensions must be in [1,2000]");
    }
    this.embeddingDimensions = embeddingDimensions;
    this.vectorType = "vector(" + embeddingDimensions + ")";
  }

  @Override
  public List<ComponentCandidate> findCandidates(CandidateStoreQuery query) {
    Objects.requireNonNull(query, "query must not be null");
    List<ComponentCandidate> candidates = new ArrayList<>();
    if (query.sources().contains(CandidateSource.VECTOR)) {
      candidates.addAll(vector(query));
    }
    if (query.sources().contains(CandidateSource.LEXICAL)) {
      candidates.addAll(lexical(query));
    }
    if (query.sources().contains(CandidateSource.STRUCTURED)) {
      candidates.addAll(structured(query));
    }
    if (query.sources().contains(CandidateSource.TEMPORAL)) {
      candidates.addAll(temporal(query));
    }
    return List.copyOf(candidates);
  }

  private List<ComponentCandidate> vector(CandidateStoreQuery query) {
    if (query.embedding().dimensions() != embeddingDimensions) {
      throw new IllegalArgumentException("retrieval embedding dimension mismatch");
    }
    String vector = vector(query.embedding().vector());
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + ", 1.0 - ((projection.embedding::"
            + vectorType
            + ") <=> ?::"
            + vectorType
            + ") AS raw_score\n"
            + """
              FROM memos.memory_search_projection projection
             WHERE projection.tenant_id = ? AND projection.user_id = ?
               AND projection.agent_id = ?
               AND projection.embedding_model_version = ?
               AND projection.embedding_dimensions = ?
            """
            + VISIBILITY
            + " ORDER BY (projection.embedding::"
            + vectorType
            + ") <=> ?::"
            + vectorType
            + ", projection.version_id\n"
            + """
             LIMIT ?
            """,
        (result, row) -> candidate(result, CandidateSource.VECTOR, row + 1),
        vector,
        query.scope().tenantId(),
        query.scope().userId(),
        query.scope().agentId(),
        query.embedding().modelVersion(),
        embeddingDimensions,
        query.intent().temporal().name(),
        timestamp(query.targetTime()),
        timestamp(query.targetTime()),
        timestamp(query.targetTime()),
        vector,
        query.componentLimit());
  }

  private List<ComponentCandidate> lexical(CandidateStoreQuery query) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + """
            , ts_rank_cd(
                  projection.lexical_document,
                  plainto_tsquery('simple'::regconfig, ?)
              ) AS raw_score
              FROM memos.memory_search_projection projection
             WHERE projection.tenant_id = ? AND projection.user_id = ?
               AND projection.agent_id = ?
               AND projection.lexical_document
                   @@ plainto_tsquery('simple'::regconfig, ?)
            """
            + VISIBILITY
            + """
             ORDER BY raw_score DESC, projection.version_id
             LIMIT ?
            """,
        (result, row) -> candidate(result, CandidateSource.LEXICAL, row + 1),
        query.query(),
        query.scope().tenantId(),
        query.scope().userId(),
        query.scope().agentId(),
        query.query(),
        query.intent().temporal().name(),
        timestamp(query.targetTime()),
        timestamp(query.targetTime()),
        timestamp(query.targetTime()),
        query.componentLimit());
  }

  private List<ComponentCandidate> structured(CandidateStoreQuery query) {
    if ((query.predicate() == null || query.predicate().isBlank())
        && (query.subjectLabel() == null || query.subjectLabel().isBlank())) {
      return List.of();
    }
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + """
            , CASE
                WHEN ?::text IS NOT NULL AND projection.predicate = ?::text THEN 1.0
                WHEN ?::text IS NOT NULL
                     AND lower(projection.subject_label) = lower(?::text) THEN 0.8
                ELSE 0.5
              END AS raw_score
              FROM memos.memory_search_projection projection
             WHERE projection.tenant_id = ? AND projection.user_id = ?
               AND projection.agent_id = ?
               AND ((?::text IS NOT NULL AND projection.predicate = ?::text)
                    OR (?::text IS NOT NULL
                        AND lower(projection.subject_label) = lower(?::text)))
            """
            + VISIBILITY
            + """
             ORDER BY raw_score DESC, projection.version_id
             LIMIT ?
            """,
        (result, row) -> candidate(result, CandidateSource.STRUCTURED, row + 1),
        query.predicate(),
        query.predicate(),
        query.subjectLabel(),
        query.subjectLabel(),
        query.scope().tenantId(),
        query.scope().userId(),
        query.scope().agentId(),
        query.predicate(),
        query.predicate(),
        query.subjectLabel(),
        query.subjectLabel(),
        query.intent().temporal().name(),
        timestamp(query.targetTime()),
        timestamp(query.targetTime()),
        timestamp(query.targetTime()),
        query.componentLimit());
  }

  private List<ComponentCandidate> temporal(CandidateStoreQuery query) {
    Timestamp target = timestamp(query.targetTime());
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + """
            , CASE
                WHEN ?::timestamptz IS NULL THEN 1.0
                WHEN (projection.valid_time_start IS NULL
                      OR projection.valid_time_start <= ?::timestamptz)
                 AND (projection.valid_time_end IS NULL
                      OR projection.valid_time_end > ?::timestamptz) THEN 1.0
                ELSE 0.0
              END AS raw_score
              FROM memos.memory_search_projection projection
             WHERE projection.tenant_id = ? AND projection.user_id = ?
               AND projection.agent_id = ?
               AND (?::timestamptz IS NULL
                    OR ((projection.valid_time_start IS NULL
                         OR projection.valid_time_start <= ?::timestamptz)
                        AND (projection.valid_time_end IS NULL
                         OR projection.valid_time_end > ?::timestamptz)))
            """
            + VISIBILITY
            + """
             ORDER BY raw_score DESC, projection.recorded_at DESC, projection.version_id
             LIMIT ?
            """,
        (result, row) -> candidate(result, CandidateSource.TEMPORAL, row + 1),
        target,
        target,
        target,
        query.scope().tenantId(),
        query.scope().userId(),
        query.scope().agentId(),
        target,
        target,
        target,
        query.intent().temporal().name(),
        target,
        target,
        target,
        query.componentLimit());
  }

  private static ComponentCandidate candidate(ResultSet result, CandidateSource source, int rank)
      throws SQLException {
    ProjectedMemory memory =
        new ProjectedMemory(
            result.getObject("memory_id", UUID.class),
            result.getObject("version_id", UUID.class),
            MemoryType.valueOf(result.getString("memory_type")),
            SubjectKind.valueOf(result.getString("subject_kind")),
            result.getString("subject_label"),
            result.getString("predicate"),
            AssertionStatus.valueOf(result.getString("truth_status")),
            result.getString("normalized_content"),
            nullableInstant(result, "valid_time_start"),
            nullableInstant(result, "valid_time_end"),
            result.getTimestamp("recorded_at").toInstant(),
            uuidList(result.getArray("source_event_ids")),
            result.getString("projection_policy_version"),
            result.getString("embedding_model_version"),
            result.getLong("transition_sequence"),
            result.getTimestamp("projected_at").toInstant());
    return new ComponentCandidate(
        memory, new ComponentSignal(source, rank, result.getDouble("raw_score")));
  }

  private static List<UUID> uuidList(Array array) throws SQLException {
    Object value = array.getArray();
    if (value instanceof UUID[] identifiers) {
      return List.of(identifiers);
    }
    return java.util.Arrays.stream((Object[]) value).map(item -> (UUID) item).toList();
  }

  private static String vector(List<Float> values) {
    return values.stream()
        .map(String::valueOf)
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
