package dev.memos.governance;

import java.util.Objects;
import java.util.UUID;

public record DeletionRequest(
    UUID operationId, DeletionRequestCommand command, String requestFingerprint) {
  public DeletionRequest {
    Objects.requireNonNull(operationId, "operationId must not be null");
    Objects.requireNonNull(command, "command must not be null");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
    if (!requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestFingerprint must be lowercase SHA-256");
    }
  }
}
