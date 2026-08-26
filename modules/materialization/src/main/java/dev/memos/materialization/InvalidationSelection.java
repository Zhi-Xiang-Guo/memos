package dev.memos.materialization;

import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MemoryLineageId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Selects an existing scoped source as evidence for an explicit invalidation. */
public record InvalidationSelection(
    LineageScope scope,
    MemoryLineageId lineageId,
    AssertionVersionId versionId,
    UUID sourceEventId,
    String idempotencyKey,
    long expectedLockVersion,
    String reason,
    String traceId,
    Instant requestedAt) {
  private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public InvalidationSelection {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(lineageId, "lineageId must not be null");
    Objects.requireNonNull(versionId, "versionId must not be null");
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    idempotencyKey =
        MaterializationTextValidation.requireText(idempotencyKey, "idempotencyKey", 200);
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion must not be negative");
    }
    reason = MaterializationTextValidation.requireText(reason, "reason", 64);
    if (!REASON_CODE.matcher(reason).matches()) {
      throw new IllegalArgumentException("reason must be an uppercase reason code");
    }
    traceId = MaterializationTextValidation.requireText(traceId, "traceId", 128);
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");
  }
}
