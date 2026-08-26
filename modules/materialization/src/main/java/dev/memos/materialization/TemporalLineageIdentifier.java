package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.governance.MemoryScope;

@FunctionalInterface
public interface TemporalLineageIdentifier {
  MemoryLineageId identify(MemoryScope scope, MemoryCandidateProposal proposal);
}
