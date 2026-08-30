package dev.memos.materialization;

import dev.memos.governance.PolicyDecision;
import dev.memos.governance.SensitivityAction;
import dev.memos.governance.WritePolicyOutcome;
import dev.memos.governance.WritePolicyReason;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CandidateExtractionJobHandler implements MaterializationJobHandler {
  private final Clock clock;
  private final SourceExtractionStore sourceStore;
  private final ExtractionCommitStore commitStore;
  private final CandidateExtractionService extractionService;
  private final ExtractionIdentifierGenerator identifierGenerator;
  private final ExtractionProviderIdentity providerIdentity;
  private final String policyVersion;

  public CandidateExtractionJobHandler(
      Clock clock,
      SourceExtractionStore sourceStore,
      ExtractionCommitStore commitStore,
      CandidateExtractionService extractionService,
      ExtractionIdentifierGenerator identifierGenerator,
      ExtractionProviderIdentity providerIdentity,
      String policyVersion) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.sourceStore = Objects.requireNonNull(sourceStore, "sourceStore must not be null");
    this.commitStore = Objects.requireNonNull(commitStore, "commitStore must not be null");
    this.extractionService =
        Objects.requireNonNull(extractionService, "extractionService must not be null");
    this.identifierGenerator =
        Objects.requireNonNull(identifierGenerator, "identifierGenerator must not be null");
    this.providerIdentity =
        Objects.requireNonNull(providerIdentity, "providerIdentity must not be null");
    this.policyVersion =
        MaterializationTextValidation.requireText(policyVersion, "policyVersion", 128);
  }

  @Override
  public JobHandlingResult handle(ClaimedJob job) throws JobHandlingException {
    if (job.jobType() != JobType.MATERIALIZE_SOURCE) {
      throw JobHandlingException.permanentFailure("UnsupportedExtractionJobType");
    }
    Optional<SourceForExtraction> source = sourceStore.find(job.scope(), job.sourceEventId());
    if (source.isEmpty()) {
      return commitSkipped(job, SkippedExtractionReason.SOURCE_NOT_FOUND);
    }
    if (source.orElseThrow().contentState() != SourceContentState.ACTIVE) {
      return commitSkipped(job, SkippedExtractionReason.SOURCE_NOT_ACTIVE);
    }
    if (!job.modelVersion().equals(providerIdentity.modelVersion())) {
      throw JobHandlingException.permanentFailure("ExtractionModelVersionMismatch");
    }

    ExtractionAttemptId attemptId = identifierGenerator.attemptIdFor(job);
    ExtractionAttemptStartResult startResult =
        commitStore.startAttempt(
            new StartExtractionAttempt(
                attemptId, job, providerIdentity, policyVersion, clock.instant()));
    if (startResult == ExtractionAttemptStartResult.LEASE_LOST) {
      return JobHandlingResult.LEASE_LOST;
    }

    CandidateExtractionEvaluation evaluation;
    try {
      evaluation = extractionService.evaluate(job, source.orElseThrow(), providerIdentity);
    } catch (CandidateExtractionProviderException exception) {
      recordFailure(attemptId, job, exception.kind(), exception.errorClass());
      throw exception.kind() == JobFailureKind.TRANSIENT
          ? JobHandlingException.transientFailure(exception.errorClass().value())
          : JobHandlingException.permanentFailure(exception.errorClass().value());
    } catch (RuntimeException exception) {
      String simpleName = exception.getClass().getSimpleName();
      JobErrorClass errorClass =
          new JobErrorClass(simpleName.isBlank() ? "ProviderRuntimeFailure" : simpleName);
      recordFailure(attemptId, job, JobFailureKind.TRANSIENT, errorClass);
      throw JobHandlingException.transientFailure(errorClass.value());
    }
    if (evaluation instanceof InvalidExtractionEvaluation invalid) {
      ExtractionCommitResult result =
          commitStore.commitInvalidSchema(
              new CommitInvalidExtraction(
                  attemptId,
                  identifierGenerator.invalidResponseQuarantineIdFor(job),
                  job,
                  invalid.providerMetadata(),
                  invalid.error(),
                  invalid.path(),
                  clock.instant()));
      return terminalResult(result, JobHandlingResult.DEAD_ATOMICALLY);
    }

    ValidExtractionEvaluation valid = (ValidExtractionEvaluation) evaluation;
    List<CandidateCommitRecord> candidates = sanitizedCandidates(job, valid.candidates());
    List<CandidateQuarantineRecord> quarantines = quarantines(job, valid.candidates());
    boolean hasRememberedCandidate =
        candidates.stream()
            .anyMatch(candidate -> candidate.contentState() == CandidateContentState.AVAILABLE);
    DownstreamMaterializationIntent downstream =
        hasRememberedCandidate
            ? new DownstreamMaterializationIntent(
                new SemanticJobKey(
                    "CANDIDATE_MATERIALIZATION/" + job.sourceEventId() + "/" + policyVersion),
                JobType.CANDIDATE_MATERIALIZATION)
            : null;
    ExtractionCommitResult result =
        commitStore.commitSuccess(
            new CommitExtractionSuccess(
                attemptId,
                identifierGenerator.runIdFor(job),
                job,
                "EXTRACTION/" + job.sourceEventId() + "/" + policyVersion,
                valid.providerMetadata(),
                policyVersion,
                candidates,
                quarantines,
                downstream,
                clock.instant()));
    return terminalResult(result, JobHandlingResult.COMPLETED_ATOMICALLY);
  }

  private void recordFailure(
      ExtractionAttemptId attemptId,
      ClaimedJob job,
      JobFailureKind kind,
      JobErrorClass errorClass) {
    commitStore.recordFailure(
        new RecordExtractionFailure(attemptId, job, kind, errorClass, null, clock.instant()));
  }

  private JobHandlingResult commitSkipped(ClaimedJob job, SkippedExtractionReason reason) {
    ExtractionCommitResult result =
        commitStore.commitSkipped(new CommitSkippedExtraction(job, reason, clock.instant()));
    return terminalResult(result, JobHandlingResult.COMPLETED_ATOMICALLY);
  }

  private List<CandidateCommitRecord> sanitizedCandidates(
      ClaimedJob job, List<EvaluatedCandidate> evaluatedCandidates) {
    List<CandidateCommitRecord> records = new ArrayList<>(evaluatedCandidates.size());
    for (EvaluatedCandidate candidate : evaluatedCandidates) {
      WritePolicyOutcome outcome = candidate.policyOutcome();
      boolean mayRetain =
          outcome.decision() == PolicyDecision.REMEMBER
              && (outcome.sensitivityAction() == SensitivityAction.NONE
                  || outcome.sensitivityAction() == SensitivityAction.RESTRICT);
      records.add(
          new CandidateCommitRecord(
              candidate.candidateId(),
              candidate.ordinal(),
              mayRetain ? CandidateContentState.AVAILABLE : CandidateContentState.ERASED,
              mayRetain ? outcome.effectiveProposal() : null,
              new CandidatePolicyDecisionRecord(
                  outcome.decision(),
                  outcome.sensitivityAction(),
                  outcome.reasons(),
                  policyVersion)));
    }
    return List.copyOf(records);
  }

  private List<CandidateQuarantineRecord> quarantines(
      ClaimedJob job, List<EvaluatedCandidate> evaluatedCandidates) {
    List<CandidateQuarantineRecord> quarantines = new ArrayList<>();
    for (EvaluatedCandidate candidate : evaluatedCandidates) {
      WritePolicyOutcome outcome = candidate.policyOutcome();
      boolean contentErased =
          outcome.decision() != PolicyDecision.REMEMBER
              || (outcome.sensitivityAction() != SensitivityAction.NONE
                  && outcome.sensitivityAction() != SensitivityAction.RESTRICT);
      if (contentErased) {
        WritePolicyReason primaryReason = outcome.reasons().getFirst();
        quarantines.add(
            new CandidateQuarantineRecord(
                identifierGenerator.quarantineIdFor(job, candidate.ordinal(), primaryReason),
                candidate.candidateId(),
                candidate.ordinal(),
                primaryReason));
      }
    }
    return List.copyOf(quarantines);
  }

  private static JobHandlingResult terminalResult(
      ExtractionCommitResult result, JobHandlingResult committedResult) {
    return result == ExtractionCommitResult.LEASE_LOST
        ? JobHandlingResult.LEASE_LOST
        : committedResult;
  }
}
