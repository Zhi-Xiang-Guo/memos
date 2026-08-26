package dev.memos.materialization;

import dev.memos.governance.WritePolicyReason;
import java.util.Objects;

public record CandidateQuarantineRecord(
    QuarantineId quarantineId, CandidateId candidateId, int ordinal, WritePolicyReason reason) {
  public CandidateQuarantineRecord {
    Objects.requireNonNull(quarantineId, "quarantineId must not be null");
    Objects.requireNonNull(candidateId, "candidateId must not be null");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    Objects.requireNonNull(reason, "reason must not be null");
  }
}
