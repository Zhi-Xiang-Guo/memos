package dev.memos.adapters.system;

import dev.memos.governance.WritePolicyReason;
import dev.memos.materialization.CandidateId;
import dev.memos.materialization.ClaimedJob;
import dev.memos.materialization.ExtractionAttemptId;
import dev.memos.materialization.ExtractionIdentifierGenerator;
import dev.memos.materialization.ExtractionRunId;
import dev.memos.materialization.QuarantineId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable logical IDs make retries idempotent while lease-scoped attempts remain distinct. */
public final class DeterministicExtractionIdentifierGenerator
    implements ExtractionIdentifierGenerator {
  @Override
  public ExtractionAttemptId attemptIdFor(ClaimedJob job) {
    return new ExtractionAttemptId(
        uuid("attempt/" + job.jobId() + "/" + job.attempt() + "/" + job.leaseToken()));
  }

  @Override
  public ExtractionRunId runIdFor(ClaimedJob job) {
    return new ExtractionRunId(uuid("run/" + job.scope().tenantId() + "/" + job.jobId()));
  }

  @Override
  public CandidateId candidateIdFor(ClaimedJob job, int ordinal) {
    return new CandidateId(
        uuid("candidate/" + job.scope().tenantId() + "/" + job.jobId() + "/" + ordinal));
  }

  @Override
  public QuarantineId quarantineIdFor(ClaimedJob job, int ordinal, WritePolicyReason reason) {
    return new QuarantineId(
        uuid(
            "quarantine/"
                + job.scope().tenantId()
                + "/"
                + job.jobId()
                + "/"
                + ordinal
                + "/"
                + reason));
  }

  @Override
  public QuarantineId invalidResponseQuarantineIdFor(ClaimedJob job) {
    return new QuarantineId(uuid("invalid-response/" + job.scope().tenantId() + "/" + job.jobId()));
  }

  private static UUID uuid(String identity) {
    return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
  }
}
