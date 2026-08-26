package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.memos.domain.candidate.EvidenceTrust;
import dev.memos.governance.DeterministicCandidateWritePolicy;
import dev.memos.governance.MemoryScope;
import dev.memos.governance.NoveltyAssessment;
import dev.memos.governance.WritePolicyConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateExtractionJobHandlerTest {
  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
  private static final ExtractionProviderIdentity IDENTITY =
      new ExtractionProviderIdentity(
          "fake", "deterministic-fixture-v1", "candidate-extraction-v1", "memory-candidate.v1");

  @Test
  void erasedSourceTerminatesWithoutCallingProvider() throws Exception {
    CapturingCommitStore commitStore = new CapturingCommitStore();
    ProviderFake provider = new ProviderFake(validPreferenceJson());
    SourceForExtraction erased =
        new SourceForExtraction(
            job().sourceEventId(),
            scope(),
            scope(),
            ExtractionActorType.USER,
            ExtractionSourceType.CONVERSATION_MESSAGE,
            EvidenceTrust.DIRECT_USER,
            SourceContentState.ERASED,
            null,
            Map.of(),
            Map.of(),
            Set.of(),
            true);

    JobHandlingResult result = handler(provider, commitStore, erased).handle(job());

    assertEquals(JobHandlingResult.COMPLETED_ATOMICALLY, result);
    assertFalse(provider.called);
    assertEquals(SkippedExtractionReason.SOURCE_NOT_ACTIVE, commitStore.skipped.reason());
  }

  @Test
  void rejectedSecretIsCommittedWithoutContentAndWithoutDownstreamIntent() throws Exception {
    CapturingCommitStore commitStore = new CapturingCommitStore();
    ProviderFake provider = new ProviderFake(secretJson());

    JobHandlingResult result = handler(provider, commitStore, activeSource()).handle(job());

    assertEquals(JobHandlingResult.COMPLETED_ATOMICALLY, result);
    assertTrue(provider.called);
    CandidateCommitRecord candidate = commitStore.success.candidates().getFirst();
    assertEquals(CandidateContentState.ERASED, candidate.contentState());
    assertNull(candidate.content());
    assertEquals(1, commitStore.success.quarantines().size());
    assertNull(commitStore.success.downstreamIntent());
  }

  @Test
  void safeRememberedCandidateCreatesCandidateMaterializationIntent() throws Exception {
    CapturingCommitStore commitStore = new CapturingCommitStore();

    JobHandlingResult result =
        handler(new ProviderFake(validPreferenceJson()), commitStore, activeSource()).handle(job());

    assertEquals(JobHandlingResult.COMPLETED_ATOMICALLY, result);
    assertEquals(
        CandidateContentState.AVAILABLE,
        commitStore.success.candidates().getFirst().contentState());
    assertEquals(
        JobType.CANDIDATE_MATERIALIZATION, commitStore.success.downstreamIntent().jobType());
  }

  @Test
  void invalidSchemaIsQuarantinedContentFreeAndCompletedDeadAtomically() throws Exception {
    CapturingCommitStore commitStore = new CapturingCommitStore();

    JobHandlingResult result =
        handler(
                new ProviderFake("{\"schema_version\":\"wrong\",\"candidates\":[]}"),
                commitStore,
                activeSource())
            .handle(job());

    assertEquals(JobHandlingResult.DEAD_ATOMICALLY, result);
    assertEquals(
        ProposalDecodingError.SCHEMA_VERSION_MISMATCH, commitStore.invalid.decodingError());
    assertNull(commitStore.success);
  }

  private static CandidateExtractionJobHandler handler(
      ProviderFake provider, CapturingCommitStore commitStore, SourceForExtraction source) {
    ExtractionIdentifierGenerator identifiers = new FixedIdentifiers();
    CandidateExtractionService service =
        new CandidateExtractionService(
            provider,
            new StrictCandidateProposalDecoder(),
            new DeterministicCandidateWritePolicy(WritePolicyConfiguration.safeDefaults()),
            identifiers);
    return new CandidateExtractionJobHandler(
        Clock.fixed(NOW, ZoneOffset.UTC),
        (scope, sourceEventId) -> Optional.of(source),
        commitStore,
        service,
        identifiers,
        IDENTITY,
        "write-policy-v1");
  }

  private static SourceForExtraction activeSource() {
    return new SourceForExtraction(
        job().sourceEventId(),
        scope(),
        scope(),
        ExtractionActorType.USER,
        ExtractionSourceType.CONVERSATION_MESSAGE,
        EvidenceTrust.DIRECT_USER,
        SourceContentState.ACTIVE,
        "source content",
        Map.of(),
        Map.of(0, NoveltyAssessment.NEW),
        Set.of(),
        true);
  }

  private static ClaimedJob job() {
    return new ClaimedJob(
        new JobId(new UUID(0, 1)),
        JobType.MATERIALIZE_SOURCE,
        scope(),
        new UUID(0, 2),
        new SemanticJobKey("MATERIALIZE_SOURCE/source/write-policy-v1"),
        "ingestion-v1",
        "deterministic-fixture-v1",
        1,
        5,
        new WorkerId("worker-1"),
        new LeaseToken(new UUID(0, 3)),
        NOW.plus(Duration.ofMinutes(1)),
        "trace-1");
  }

  private static MemoryScope scope() {
    return new MemoryScope("tenant-1", "user-1", "agent-1");
  }

  private static String validPreferenceJson() {
    return candidateJson("preference.editor.theme", "dark", "[\"NONE\"]");
  }

  private static String secretJson() {
    return candidateJson("credential.synthetic_marker", "<test-only-secret>", "[\"NONE\"]");
  }

  private static String candidateJson(String predicate, String value, String sensitivity) {
    return """
        {"schema_version":"memory-candidate.v1","candidates":[{
          "proposed_decision":"REMEMBER","memory_type":"SEMANTIC",
          "subject":{"kind":"USER","label":null},
          "predicate":"%s","value":"%s",
          "normalized_content":"Normalized candidate content.",
          "event_time":null,"valid_interval":null,
          "importance":0.8,"confidence":0.99,
          "sensitivity":%s,"candidate_relations":[]
        }]}
        """
        .formatted(predicate, value, sensitivity);
  }

  private static final class ProviderFake implements StructuredCandidateExtractionPort {
    private final String rawJson;
    private boolean called;

    private ProviderFake(String rawJson) {
      this.rawJson = rawJson;
    }

    @Override
    public RawExtractionResponse extract(CandidateExtractionRequest request) {
      called = true;
      return new RawExtractionResponse(
          rawJson,
          new ProviderCallMetadata(
              IDENTITY.provider(),
              IDENTITY.modelVersion(),
              IDENTITY.promptVersion(),
              IDENTITY.schemaVersion(),
              "call-1",
              new ProviderTokenUsage(10, 10),
              Duration.ofMillis(5)));
    }
  }

  private static final class CapturingCommitStore implements ExtractionCommitStore {
    private CommitExtractionSuccess success;
    private CommitInvalidExtraction invalid;
    private CommitSkippedExtraction skipped;

    @Override
    public ExtractionAttemptStartResult startAttempt(StartExtractionAttempt command) {
      return ExtractionAttemptStartResult.STARTED;
    }

    @Override
    public ExtractionCommitResult commitSuccess(CommitExtractionSuccess command) {
      success = command;
      return ExtractionCommitResult.COMMITTED;
    }

    @Override
    public ExtractionCommitResult commitInvalidSchema(CommitInvalidExtraction command) {
      invalid = command;
      return ExtractionCommitResult.COMMITTED;
    }

    @Override
    public ExtractionCommitResult commitSkipped(CommitSkippedExtraction command) {
      skipped = command;
      return ExtractionCommitResult.COMMITTED;
    }

    @Override
    public void recordTransientFailure(RecordTransientExtractionFailure command) {}
  }

  private static final class FixedIdentifiers implements ExtractionIdentifierGenerator {
    @Override
    public ExtractionAttemptId attemptIdFor(ClaimedJob job) {
      return new ExtractionAttemptId(new UUID(1, 1));
    }

    @Override
    public ExtractionRunId runIdFor(ClaimedJob job) {
      return new ExtractionRunId(new UUID(1, 2));
    }

    @Override
    public CandidateId candidateIdFor(ClaimedJob job, int ordinal) {
      return new CandidateId(new UUID(1, 10 + ordinal));
    }

    @Override
    public QuarantineId quarantineIdFor(
        ClaimedJob job, int ordinal, dev.memos.governance.WritePolicyReason reason) {
      return new QuarantineId(new UUID(2, 10 + ordinal));
    }

    @Override
    public QuarantineId invalidResponseQuarantineIdFor(ClaimedJob job) {
      return new QuarantineId(new UUID(2, 1));
    }
  }
}
