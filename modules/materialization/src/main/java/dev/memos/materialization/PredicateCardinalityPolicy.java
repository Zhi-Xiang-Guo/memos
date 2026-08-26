package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.temporal.PredicateCardinality;

@FunctionalInterface
public interface PredicateCardinalityPolicy {
  PredicateCardinality cardinality(MemoryCandidateProposal proposal);
}
