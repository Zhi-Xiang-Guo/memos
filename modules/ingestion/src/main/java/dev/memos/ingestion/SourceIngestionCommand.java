package dev.memos.ingestion;

import dev.memos.governance.MemoryScope;
import java.time.Instant;
import java.util.Objects;

public record SourceIngestionCommand(
    MemoryScope scope,
    String sourceId,
    String sessionId,
    String idempotencyKey,
    ActorType actorType,
    SourceType sourceType,
    TrustLevel trustLevel,
    Instant occurredAt,
    String payload,
    String traceId) {
  private static final int MAX_SCOPE_ID_LENGTH = 128;
  private static final int MAX_EXTERNAL_ID_LENGTH = 200;
  private static final int MAX_TRACE_ID_LENGTH = 128;
  private static final int MAX_PAYLOAD_BYTES = 65_536;

  public SourceIngestionCommand {
    Objects.requireNonNull(scope, "scope must not be null");
    TextValidation.requireText(scope.tenantId(), "tenantId", MAX_SCOPE_ID_LENGTH);
    TextValidation.requireText(scope.userId(), "userId", MAX_SCOPE_ID_LENGTH);
    TextValidation.requireText(scope.agentId(), "agentId", MAX_SCOPE_ID_LENGTH);
    sourceId = TextValidation.requireText(sourceId, "sourceId", MAX_EXTERNAL_ID_LENGTH);
    sessionId = TextValidation.requireText(sessionId, "sessionId", MAX_EXTERNAL_ID_LENGTH);
    idempotencyKey =
        TextValidation.requireText(idempotencyKey, "idempotencyKey", MAX_EXTERNAL_ID_LENGTH);
    Objects.requireNonNull(actorType, "actorType must not be null");
    Objects.requireNonNull(sourceType, "sourceType must not be null");
    Objects.requireNonNull(trustLevel, "trustLevel must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    payload = TextValidation.requirePayload(payload, MAX_PAYLOAD_BYTES);
    traceId = TextValidation.requireText(traceId, "traceId", MAX_TRACE_ID_LENGTH);
  }
}
