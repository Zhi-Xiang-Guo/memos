package dev.memos.governance;

import dev.memos.domain.candidate.EvidenceTrust;
import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.ProposalDecision;
import dev.memos.domain.candidate.SensitivityCategory;
import dev.memos.domain.candidate.SubjectKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DeterministicCandidateWritePolicy implements CandidateWritePolicy {
  private final WritePolicyConfiguration configuration;
  private final SensitivityDetector sensitivityDetector;

  public DeterministicCandidateWritePolicy(WritePolicyConfiguration configuration) {
    this(configuration, new DeterministicSensitivityDetector());
  }

  public DeterministicCandidateWritePolicy(
      WritePolicyConfiguration configuration, SensitivityDetector sensitivityDetector) {
    this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    this.sensitivityDetector =
        Objects.requireNonNull(sensitivityDetector, "sensitivityDetector must not be null");
  }

  @Override
  public WritePolicyOutcome evaluate(MemoryCandidateProposal proposal, WritePolicyContext context) {
    Objects.requireNonNull(proposal, "proposal must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Set<SensitivityCategory> effectiveSensitivity = effectiveSensitivity(proposal);
    SensitivityAction sensitivityAction = strongestSensitivityAction(effectiveSensitivity, context);

    if (sensitivityAction == SensitivityAction.REJECT) {
      WritePolicyReason reason =
          effectiveSensitivity.contains(SensitivityCategory.AUTH_SECRET)
                  || effectiveSensitivity.contains(SensitivityCategory.CREDENTIAL)
              ? WritePolicyReason.SECRET_REJECTED
              : WritePolicyReason.SENSITIVE_REVIEW;
      return outcome(proposal, PolicyDecision.IGNORE, SensitivityAction.REJECT, reason);
    }
    if (proposal.memoryType() == MemoryType.WORKING && proposal.validInterval() == null) {
      return outcome(
          proposal,
          PolicyDecision.IGNORE,
          SensitivityAction.REJECT,
          WritePolicyReason.TEMPORARY_WITHOUT_EXPIRY);
    }
    if (proposal.memoryType() == MemoryType.PROCEDURAL && isUntrusted(context.sourceTrust())) {
      return outcome(
          proposal,
          PolicyDecision.IGNORE,
          SensitivityAction.REJECT,
          WritePolicyReason.UNTRUSTED_PROCEDURAL_SOURCE);
    }
    if (!scopeAuthorized(proposal, context)) {
      return outcome(
          proposal,
          PolicyDecision.REVIEW,
          SensitivityAction.NONE,
          WritePolicyReason.SCOPE_NOT_AUTHORIZED);
    }
    if (context.novelty() == NoveltyAssessment.DUPLICATE_EXACT) {
      return outcome(
          proposal,
          PolicyDecision.IGNORE,
          SensitivityAction.NONE,
          WritePolicyReason.DUPLICATE_EXACT);
    }
    if (proposal.proposedDecision() == ProposalDecision.IGNORE) {
      return outcome(
          proposal,
          PolicyDecision.IGNORE,
          sensitivityAction,
          WritePolicyReason.MODEL_PROPOSED_IGNORE);
    }
    if (proposal.memoryType() == MemoryType.PROCEDURAL) {
      if (!context.hasCapability(WriteCapability.WRITE_PROCEDURAL_MEMORY)) {
        return outcome(
            proposal,
            PolicyDecision.REVIEW,
            sensitivityAction,
            WritePolicyReason.PROCEDURAL_APPROVAL_REQUIRED);
      }
      if (proposal.confidence() < configuration.proceduralConfidenceMinimum()
          || proposal.importance() < configuration.proceduralImportanceMinimum()) {
        return outcome(
            proposal,
            PolicyDecision.REVIEW,
            sensitivityAction,
            WritePolicyReason.PROCEDURAL_THRESHOLD_NOT_MET);
      }
    }

    List<WritePolicyReason> reviewReasons = new ArrayList<>();
    SensitivityAction effectiveAction = sensitivityAction;
    if (proposal.confidence() < configuration.reviewConfidenceBelow()) {
      reviewReasons.add(WritePolicyReason.LOW_CONFIDENCE);
    }
    if (sensitivityAction == SensitivityAction.TOKENIZE) {
      if (context.tokenizerAvailable()) {
        reviewReasons.add(WritePolicyReason.SENSITIVE_REVIEW);
      } else {
        effectiveAction = SensitivityAction.REVIEW;
        reviewReasons.add(WritePolicyReason.TOKENIZER_UNAVAILABLE);
      }
    } else if (sensitivityAction == SensitivityAction.RESTRICT
        || sensitivityAction == SensitivityAction.REVIEW
        || sensitivityAction == SensitivityAction.REDACT) {
      reviewReasons.add(WritePolicyReason.SENSITIVE_REVIEW);
    }
    if (isUntrusted(context.sourceTrust())) {
      reviewReasons.add(WritePolicyReason.UNTRUSTED_SOURCE);
    }
    if (context.novelty() == NoveltyAssessment.POSSIBLE_DUPLICATE) {
      reviewReasons.add(WritePolicyReason.POSSIBLE_DUPLICATE);
    }
    if (proposal.proposedDecision() == ProposalDecision.REVIEW && reviewReasons.isEmpty()) {
      reviewReasons.add(WritePolicyReason.MODEL_PROPOSED_REVIEW);
    }
    if (!reviewReasons.isEmpty()) {
      return outcome(proposal, PolicyDecision.REVIEW, effectiveAction, reviewReasons);
    }
    return outcome(
        proposal,
        PolicyDecision.REMEMBER,
        SensitivityAction.NONE,
        WritePolicyReason.POLICY_ACCEPTED);
  }

  private Set<SensitivityCategory> effectiveSensitivity(MemoryCandidateProposal proposal) {
    EnumSet<SensitivityCategory> union = EnumSet.noneOf(SensitivityCategory.class);
    union.addAll(proposal.sensitivity());
    union.addAll(sensitivityDetector.detect(proposal));
    if (union.size() > 1) {
      union.remove(SensitivityCategory.NONE);
    }
    if (union.isEmpty()) {
      union.add(SensitivityCategory.NONE);
    }
    return Set.copyOf(union);
  }

  private SensitivityAction strongestSensitivityAction(
      Set<SensitivityCategory> categories, WritePolicyContext context) {
    SensitivityAction strongest = SensitivityAction.NONE;
    for (SensitivityCategory category : categories) {
      SensitivityAction candidate = configuration.sensitivityActions().get(category);
      if (priority(candidate) > priority(strongest)) {
        strongest = candidate;
      }
    }
    return strongest;
  }

  private static boolean scopeAuthorized(
      MemoryCandidateProposal proposal, WritePolicyContext context) {
    if (!context.sourceScope().equals(context.targetScope())) {
      return false;
    }
    return proposal.subject().kind() != SubjectKind.PROJECT
        || context.hasCapability(WriteCapability.WRITE_PROJECT_MEMORY);
  }

  private static boolean isUntrusted(EvidenceTrust trust) {
    return trust == EvidenceTrust.ASSISTANT
        || trust == EvidenceTrust.TOOL
        || trust == EvidenceTrust.WEB;
  }

  private static int priority(SensitivityAction action) {
    return switch (action) {
      case NONE -> 0;
      case RESTRICT -> 1;
      case REVIEW -> 2;
      case TOKENIZE -> 3;
      case REDACT -> 4;
      case REJECT -> 5;
    };
  }

  private static WritePolicyOutcome outcome(
      MemoryCandidateProposal proposal,
      PolicyDecision decision,
      SensitivityAction sensitivityAction,
      WritePolicyReason reason) {
    return outcome(proposal, decision, sensitivityAction, List.of(reason));
  }

  private static WritePolicyOutcome outcome(
      MemoryCandidateProposal proposal,
      PolicyDecision decision,
      SensitivityAction sensitivityAction,
      List<WritePolicyReason> reasons) {
    return new WritePolicyOutcome(
        proposal,
        decision,
        sensitivityAction,
        sensitivityAction == SensitivityAction.RESTRICT,
        reasons);
  }
}
