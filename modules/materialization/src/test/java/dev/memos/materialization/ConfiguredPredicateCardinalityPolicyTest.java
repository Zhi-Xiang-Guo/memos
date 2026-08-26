package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.ProposalDecision;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.domain.temporal.PredicateCardinality;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfiguredPredicateCardinalityPolicyTest {
  @Test
  void configuredPredicatesAreSetValuedAndUnknownPredicatesRemainSingleValued() {
    ConfiguredPredicateCardinalityPolicy policy =
        new ConfiguredPredicateCardinalityPolicy(
            Set.of("preference.cuisine", "preference.favorite.color"));

    assertEquals(PredicateCardinality.SET, policy.cardinality(proposal("preference.cuisine")));
    assertEquals(
        PredicateCardinality.SET, policy.cardinality(proposal("preference.favorite.color")));
    assertEquals(
        PredicateCardinality.SINGLE, policy.cardinality(proposal("preference.editor.theme")));
  }

  private static MemoryCandidateProposal proposal(String predicate) {
    return new MemoryCandidateProposal(
        ProposalDecision.REMEMBER,
        MemoryType.SEMANTIC,
        new CandidateSubject(SubjectKind.USER, null),
        predicate,
        new TextCandidateValue("test-value"),
        "Normalized test content.",
        null,
        null,
        0.5,
        0.9,
        Set.of(SensitivityCategory.NONE),
        List.of());
  }
}
