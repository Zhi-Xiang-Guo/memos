package dev.memos.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.EvidenceTrust;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.ProposalDecision;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TextCandidateValue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicCandidateWritePolicyTest {
  private final DeterministicCandidateWritePolicy policy =
      new DeterministicCandidateWritePolicy(WritePolicyConfiguration.safeDefaults());

  @Test
  void acceptsTrustedHighConfidenceNovelCandidate() {
    WritePolicyOutcome outcome = policy.evaluate(candidate(), context(EvidenceTrust.DIRECT_USER));

    assertEquals(PolicyDecision.REMEMBER, outcome.decision());
    assertEquals(SensitivityAction.NONE, outcome.sensitivityAction());
    assertEquals(List.of(WritePolicyReason.POLICY_ACCEPTED), outcome.reasons());
  }

  @Test
  void deterministicDetectorCanOnlyUpgradeModelSensitivity() {
    MemoryCandidateProposal proposedSafe =
        candidate(
            MemoryType.SEMANTIC,
            SubjectKind.USER,
            null,
            "credential.synthetic_marker",
            "<test-only-secret>",
            0.99,
            Set.of(SensitivityCategory.NONE));

    WritePolicyOutcome outcome = policy.evaluate(proposedSafe, context(EvidenceTrust.DIRECT_USER));

    assertEquals(PolicyDecision.IGNORE, outcome.decision());
    assertEquals(SensitivityAction.REJECT, outcome.sensitivityAction());
    assertEquals(List.of(WritePolicyReason.SECRET_REJECTED), outcome.reasons());
  }

  @Test
  void failsClosedWhenTokenizerIsUnavailable() {
    MemoryCandidateProposal contact =
        candidate(
            MemoryType.SEMANTIC,
            SubjectKind.USER,
            null,
            "profile.contact.email",
            "user@example.invalid",
            0.99,
            Set.of(SensitivityCategory.CONTACT));
    WritePolicyContext context =
        new WritePolicyContext(
            scope(), scope(), EvidenceTrust.DIRECT_USER, NoveltyAssessment.NEW, Set.of(), false);

    WritePolicyOutcome outcome = policy.evaluate(contact, context);

    assertEquals(PolicyDecision.REVIEW, outcome.decision());
    assertEquals(SensitivityAction.REVIEW, outcome.sensitivityAction());
    assertEquals(List.of(WritePolicyReason.TOKENIZER_UNAVAILABLE), outcome.reasons());
  }

  @Test
  void rejectsUntrustedProceduralProposal() {
    MemoryCandidateProposal procedural =
        candidate(
            MemoryType.PROCEDURAL,
            SubjectKind.AGENT,
            null,
            "behavior.disclose_private_memory",
            "true",
            0.99,
            Set.of(SensitivityCategory.NONE));

    WritePolicyOutcome outcome = policy.evaluate(procedural, context(EvidenceTrust.WEB));

    assertEquals(PolicyDecision.IGNORE, outcome.decision());
    assertEquals(List.of(WritePolicyReason.UNTRUSTED_PROCEDURAL_SOURCE), outcome.reasons());
  }

  @Test
  void requiresCapabilityForProjectScope() {
    MemoryCandidateProposal project =
        candidate(
            MemoryType.SEMANTIC,
            SubjectKind.PROJECT,
            "other-team",
            "preference.answer.style",
            "verbose",
            0.95,
            Set.of(SensitivityCategory.NONE));

    WritePolicyOutcome outcome = policy.evaluate(project, context(EvidenceTrust.DIRECT_USER));

    assertEquals(PolicyDecision.REVIEW, outcome.decision());
    assertEquals(List.of(WritePolicyReason.SCOPE_NOT_AUTHORIZED), outcome.reasons());
  }

  @Test
  void exactDuplicateIsIgnoredDeterministically() {
    WritePolicyContext context =
        new WritePolicyContext(
            scope(),
            scope(),
            EvidenceTrust.DIRECT_USER,
            NoveltyAssessment.DUPLICATE_EXACT,
            Set.of(),
            true);

    WritePolicyOutcome outcome = policy.evaluate(candidate(), context);

    assertEquals(PolicyDecision.IGNORE, outcome.decision());
    assertEquals(List.of(WritePolicyReason.DUPLICATE_EXACT), outcome.reasons());
  }

  @Test
  void partialUnknownTimeCanRemainAProposalWithoutInventedBounds() {
    assertNull(candidate().eventTime());
  }

  @Test
  void isoDateIsNotMisclassifiedAsAContactPhoneNumber() {
    MemoryCandidateProposal dated =
        candidate(
            MemoryType.EPISODIC,
            SubjectKind.USER,
            null,
            "project.event.review",
            "2026-08-20",
            0.95,
            Set.of(SensitivityCategory.NONE));

    assertEquals(Set.of(), new DeterministicSensitivityDetector().detect(dated));
  }

  private static WritePolicyContext context(EvidenceTrust trust) {
    return new WritePolicyContext(scope(), scope(), trust, NoveltyAssessment.NEW, Set.of(), true);
  }

  private static MemoryScope scope() {
    return new MemoryScope("tenant-1", "user-1", "agent-1");
  }

  private static MemoryCandidateProposal candidate() {
    return candidate(
        MemoryType.SEMANTIC,
        SubjectKind.USER,
        null,
        "preference.editor.theme",
        "dark",
        0.96,
        Set.of(SensitivityCategory.NONE));
  }

  private static MemoryCandidateProposal candidate(
      MemoryType memoryType,
      SubjectKind subjectKind,
      String label,
      String predicate,
      String value,
      double confidence,
      Set<SensitivityCategory> sensitivity) {
    return new MemoryCandidateProposal(
        ProposalDecision.REMEMBER,
        memoryType,
        new CandidateSubject(subjectKind, label),
        predicate,
        new TextCandidateValue(value),
        "Normalized content for " + predicate,
        null,
        null,
        0.8,
        confidence,
        sensitivity,
        List.of());
  }
}
