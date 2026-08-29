package dev.memos.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeletionStore {
  DeletionRequestResult request(DeletionRequest request, int maxAttempts);

  Optional<DeletionOperation> find(String tenantId, String requesterSubjectId, UUID operationId);

  Optional<DeletionOperation> findForTenant(String tenantId, UUID operationId);

  Optional<DeletionOperation> requeue(DeletionRequeueCommand command, int maxAttempts);

  int deadLetterExpiredExhausted(Instant now);

  List<ClaimedDeletion> claim(String workerId, int batchSize, Instant now, Duration leaseDuration);

  DeletionStoreResult erase(ClaimedDeletion deletion, Instant completedAt);

  DeletionStoreResult scheduleRetry(
      ClaimedDeletion deletion, String errorClass, Instant nextAttemptAt, Instant updatedAt);

  DeletionStoreResult markDead(ClaimedDeletion deletion, String errorClass, Instant completedAt);
}
