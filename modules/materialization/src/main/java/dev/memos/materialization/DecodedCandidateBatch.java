package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import java.util.List;

public record DecodedCandidateBatch(
    String schemaVersion, List<MemoryCandidateProposal> candidates) {
  public DecodedCandidateBatch {
    schemaVersion = MaterializationTextValidation.requireText(schemaVersion, "schemaVersion", 128);
    candidates = List.copyOf(candidates);
    if (candidates.size() > 16) {
      throw new IllegalArgumentException("candidates must contain at most 16 entries");
    }
  }
}
