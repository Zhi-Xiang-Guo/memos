package dev.memos.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class GovernedDeletionService {
  private final DeletionStore store;
  private final DeletionIdGenerator identifiers;
  private final int maxAttempts;

  public GovernedDeletionService(
      DeletionStore store, DeletionIdGenerator identifiers, int maxAttempts) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    this.maxAttempts = maxAttempts;
  }

  public DeletionRequestResult request(DeletionRequestCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    DeletionRequest request =
        new DeletionRequest(identifiers.newOperationId(), command, fingerprint(command));
    return store.request(request, maxAttempts);
  }

  public Optional<DeletionOperation> find(
      String tenantId, String requesterSubjectId, UUID operationId) {
    return store.find(tenantId, requesterSubjectId, operationId);
  }

  public Optional<DeletionOperation> findForTenant(String tenantId, UUID operationId) {
    return store.findForTenant(tenantId, operationId);
  }

  public Optional<DeletionOperation> requeue(DeletionRequeueCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    return store.requeue(command, maxAttempts);
  }

  private static String fingerprint(DeletionRequestCommand command) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
    add(digest, command.requesterScope().tenantId());
    add(digest, command.requesterSubjectId());
    add(digest, command.authority().name());
    add(digest, command.targetType().name());
    add(digest, command.targetMemoryId() == null ? "" : command.targetMemoryId().toString());
    add(digest, command.targetUserId() == null ? "" : command.targetUserId());
    add(digest, command.policyBasis().name());
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }
}
