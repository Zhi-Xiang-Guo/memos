package dev.memos.materialization;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.temporal.PredicateCardinality;
import java.util.Objects;
import java.util.Set;

/** An explicit predicate allowlist; unknown predicates stay single-valued. */
public final class ConfiguredPredicateCardinalityPolicy implements PredicateCardinalityPolicy {
  private final Set<String> setValuedPredicates;

  public ConfiguredPredicateCardinalityPolicy(Set<String> setValuedPredicates) {
    this.setValuedPredicates =
        Set.copyOf(
            Objects.requireNonNull(setValuedPredicates, "setValuedPredicates must not be null"));
  }

  @Override
  public PredicateCardinality cardinality(MemoryCandidateProposal proposal) {
    Objects.requireNonNull(proposal, "proposal must not be null");
    return setValuedPredicates.contains(proposal.predicate())
        ? PredicateCardinality.SET
        : PredicateCardinality.SINGLE;
  }
}
