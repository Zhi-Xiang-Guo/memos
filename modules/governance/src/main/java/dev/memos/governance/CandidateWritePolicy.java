package dev.memos.governance;

import dev.memos.domain.candidate.MemoryCandidateProposal;

@FunctionalInterface
public interface CandidateWritePolicy {
  WritePolicyOutcome evaluate(MemoryCandidateProposal proposal, WritePolicyContext context);
}
