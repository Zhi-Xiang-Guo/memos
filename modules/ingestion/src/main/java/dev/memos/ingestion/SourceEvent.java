package dev.memos.ingestion;

import dev.memos.governance.MemoryScope;
import java.time.Instant;
import java.util.Objects;

public record SourceEvent(
    SourceEventId sourceEventId,
    MemoryScope scope,
    String sourceId,
    String sessionId,
    String idempotencyKey,
    ActorType actorType,
    SourceType sourceType,
    TrustLevel trustLevel,
    Instant occurredAt,
    Instant receivedAt,
    String canonicalPayload,
    Sha256Fingerprint contentFingerprint,
    Sha256Fingerprint requestFingerprint,
    SourceDeletionState deletionState,
    String traceId,
    Instant createdAt) {
  public SourceEvent {
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    TextValidation.requireText(sourceId, "sourceId", 200);
    TextValidation.requireText(sessionId, "sessionId", 200);
    TextValidation.requireText(idempotencyKey, "idempotencyKey", 200);
    Objects.requireNonNull(actorType, "actorType must not be null");
    Objects.requireNonNull(sourceType, "sourceType must not be null");
    Objects.requireNonNull(trustLevel, "trustLevel must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    TextValidation.requirePayload(canonicalPayload, 65_536);
    Objects.requireNonNull(contentFingerprint, "contentFingerprint must not be null");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
    Objects.requireNonNull(deletionState, "deletionState must not be null");
    TextValidation.requireText(traceId, "traceId", 128);
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}
