package dev.memos.governance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeletionRequestCommand(
    MemoryScope requesterScope,
    String requesterSubjectId,
    DeletionAuthority authority,
    DeletionTargetType targetType,
    UUID targetMemoryId,
    String targetUserId,
    String idempotencyKey,
    DeletionPolicyBasis policyBasis,
    String traceId,
    Instant requestedAt) {
  public DeletionRequestCommand {
    Objects.requireNonNull(requesterScope, "requesterScope must not be null");
    requesterSubjectId = requireText(requesterSubjectId, "requesterSubjectId", 200);
    Objects.requireNonNull(authority, "authority must not be null");
    Objects.requireNonNull(targetType, "targetType must not be null");
    idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 200);
    Objects.requireNonNull(policyBasis, "policyBasis must not be null");
    traceId = requireText(traceId, "traceId", 128);
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");

    if (targetType == DeletionTargetType.MEMORY) {
      Objects.requireNonNull(targetMemoryId, "memory deletion requires targetMemoryId");
      if (targetUserId != null) {
        throw new IllegalArgumentException("memory deletion must not include targetUserId");
      }
      if (authority != DeletionAuthority.SELF_SERVICE) {
        throw new IllegalArgumentException("memory deletion requires self-service authority");
      }
      if (policyBasis != DeletionPolicyBasis.USER_REQUEST) {
        throw new IllegalArgumentException("self-service deletion requires user-request basis");
      }
    } else {
      if (targetMemoryId != null) {
        throw new IllegalArgumentException("user deletion must not include targetMemoryId");
      }
      targetUserId = requireText(targetUserId, "targetUserId", 128);
      if (authority != DeletionAuthority.PRIVACY_ADMIN) {
        throw new IllegalArgumentException("user deletion requires privacy-admin authority");
      }
    }
  }

  private static String requireText(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be non-blank and bounded");
    }
    return value;
  }
}
