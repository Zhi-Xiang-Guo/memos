package dev.memos.materialization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CommitExtractionSuccess(
    ExtractionAttemptId attemptId,
    ExtractionRunId runId,
    ClaimedJob job,
    String semanticRunKey,
    ProviderCallMetadata providerMetadata,
    String policyVersion,
    List<CandidateCommitRecord> candidates,
    List<CandidateQuarantineRecord> quarantines,
    DownstreamMaterializationIntent downstreamIntent,
    Instant committedAt) {
  public CommitExtractionSuccess {
    Objects.requireNonNull(attemptId, "attemptId must not be null");
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(job, "job must not be null");
    semanticRunKey =
        MaterializationTextValidation.requireText(semanticRunKey, "semanticRunKey", 500);
    Objects.requireNonNull(providerMetadata, "providerMetadata must not be null");
    policyVersion = MaterializationTextValidation.requireText(policyVersion, "policyVersion", 128);
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    quarantines = List.copyOf(Objects.requireNonNull(quarantines, "quarantines must not be null"));
    if (candidates.size() > 16) {
      throw new IllegalArgumentException("candidates must contain at most 16 entries");
    }
    for (int ordinal = 0; ordinal < candidates.size(); ordinal++) {
      if (candidates.get(ordinal).ordinal() != ordinal) {
        throw new IllegalArgumentException("candidate ordinals must be contiguous and ordered");
      }
    }
    for (CandidateQuarantineRecord quarantine : quarantines) {
      if (quarantine.ordinal() >= candidates.size()
          || !candidates.get(quarantine.ordinal()).candidateId().equals(quarantine.candidateId())) {
        throw new IllegalArgumentException("quarantine must reference a candidate in this commit");
      }
    }
    Objects.requireNonNull(committedAt, "committedAt must not be null");
  }
}
