package dev.memos.materialization;

import dev.memos.governance.WritePolicyReason;

public interface ExtractionIdentifierGenerator {
  ExtractionAttemptId attemptIdFor(ClaimedJob job);

  ExtractionRunId runIdFor(ClaimedJob job);

  CandidateId candidateIdFor(ClaimedJob job, int ordinal);

  QuarantineId quarantineIdFor(ClaimedJob job, int ordinal, WritePolicyReason reason);

  QuarantineId invalidResponseQuarantineIdFor(ClaimedJob job);
}
