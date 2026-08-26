package dev.memos.adapters.postgres;

import dev.memos.domain.candidate.EvidenceTrust;
import dev.memos.governance.MemoryScope;
import dev.memos.materialization.ExtractionActorType;
import dev.memos.materialization.ExtractionSourceType;
import dev.memos.materialization.SourceContentState;
import dev.memos.materialization.SourceExtractionStore;
import dev.memos.materialization.SourceForExtraction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads only the tenant-scoped source fields needed by the extraction application service. */
public final class JdbcSourceExtractionStore implements SourceExtractionStore {
  private final JdbcTemplate jdbc;

  public JdbcSourceExtractionStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public Optional<SourceForExtraction> find(MemoryScope scope, UUID sourceEventId) {
    List<SourceForExtraction> rows =
        jdbc.query(
            """
            SELECT source_event_id, tenant_id, user_id, agent_id, actor_type, source_type,
                   trust_level, deletion_state,
                   CASE WHEN deletion_state = 'ACTIVE' THEN payload ->> 'content' END AS content
              FROM memos.source_event
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ? AND source_event_id = ?
            """,
            (result, row) -> map(result),
            scope.tenantId(),
            scope.userId(),
            scope.agentId(),
            sourceEventId);
    if (rows.size() > 1) {
      throw new IllegalStateException("source extraction identity invariant violated");
    }
    return rows.stream().findFirst();
  }

  private static SourceForExtraction map(ResultSet result) throws SQLException {
    String deletionState = result.getString("deletion_state");
    String content = result.getString("content");
    if ("ACTIVE".equals(deletionState) && (content == null || content.isBlank())) {
      return erased(result);
    }
    MemoryScope scope =
        new MemoryScope(
            result.getString("tenant_id"),
            result.getString("user_id"),
            result.getString("agent_id"));
    ExtractionActorType actorType = ExtractionActorType.valueOf(result.getString("actor_type"));
    ExtractionSourceType sourceType = ExtractionSourceType.valueOf(result.getString("source_type"));
    SourceContentState contentState =
        "ACTIVE".equals(deletionState) ? SourceContentState.ACTIVE : SourceContentState.ERASED;
    return new SourceForExtraction(
        result.getObject("source_event_id", UUID.class),
        scope,
        scope,
        actorType,
        sourceType,
        trust(result.getString("trust_level"), actorType),
        contentState,
        contentState == SourceContentState.ACTIVE ? content : null,
        contentState == SourceContentState.ACTIVE
            ? Map.of("actor_type", actorType.name(), "source_type", sourceType.name())
            : Map.of(),
        Map.of(),
        Set.of(),
        false);
  }

  private static SourceForExtraction erased(ResultSet result) throws SQLException {
    MemoryScope scope =
        new MemoryScope(
            result.getString("tenant_id"),
            result.getString("user_id"),
            result.getString("agent_id"));
    ExtractionActorType actorType = ExtractionActorType.valueOf(result.getString("actor_type"));
    ExtractionSourceType sourceType = ExtractionSourceType.valueOf(result.getString("source_type"));
    return new SourceForExtraction(
        result.getObject("source_event_id", UUID.class),
        scope,
        scope,
        actorType,
        sourceType,
        trust(result.getString("trust_level"), actorType),
        SourceContentState.ERASED,
        null,
        Map.of(),
        Map.of(),
        Set.of(),
        false);
  }

  private static EvidenceTrust trust(String persistedTrust, ExtractionActorType actorType) {
    return switch (persistedTrust) {
      case "DIRECT_USER" -> EvidenceTrust.DIRECT_USER;
      case "TRUSTED_APPLICATION" -> EvidenceTrust.TRUSTED_APPLICATION;
      case "ASSISTANT_GENERATED" -> EvidenceTrust.ASSISTANT;
      case "EXTERNAL_UNTRUSTED" ->
          switch (actorType) {
            case TOOL -> EvidenceTrust.TOOL;
            case WEB -> EvidenceTrust.WEB;
            default -> EvidenceTrust.WEB;
          };
      default -> throw new IllegalStateException("unsupported persisted source trust");
    };
  }
}
