package dev.memos.materialization;

import dev.memos.governance.MemoryScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MaterializationJobStore {
  int deadLetterExpiredExhaustedJobs(Instant now);

  List<ClaimedJob> claim(ClaimRequest request);

  FencedUpdateResult renewLease(LeaseFence fence, Duration leaseDuration);

  FencedUpdateResult markSucceeded(LeaseFence fence, Instant completedAt);

  FencedUpdateResult scheduleRetry(
      LeaseFence fence, JobErrorClass errorClass, Instant nextAttemptAt, Instant updatedAt);

  FencedUpdateResult markDead(LeaseFence fence, JobErrorClass errorClass, Instant completedAt);

  Optional<MaterializationJob> find(MemoryScope scope, JobId jobId);

  Optional<SourceMaterialization> findBySource(MemoryScope scope, java.util.UUID sourceEventId);

  ReplayResult replay(MemoryScope scope, JobId jobId, Instant replayedAt);
}
