package dev.memos.domain.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TemporalPrecision;
import dev.memos.domain.candidate.TextCandidateValue;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalTransitionPlannerTest {
  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");
  private static final LineageScope SCOPE = new LineageScope("tenant-1", "user-1", "agent-1");

  @Test
  void shanghaiToHangzhouPreservesFormerValueAndChangeTime() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    TransitionPlan shanghai =
        fixture.apply(
            "Shanghai",
            "The user lives in Shanghai.",
            interval("2020", "2020-01-01T00:00:00Z", "2024-01-01T00:00:00Z"),
            1,
            T0);
    TransitionPlan hangzhou =
        fixture.apply(
            "Hangzhou",
            "The user lives in Hangzhou.",
            interval("since 2024", "2024-01-01T00:00:00Z", null),
            2,
            Instant.parse("2024-01-01T00:00:00Z"));

    assertEquals(TransitionOperation.CREATE, shanghai.operation());
    assertEquals(TransitionOperation.SUPERSEDE, hangzhou.operation());
    assertEquals(
        AssertionStatus.HISTORICAL,
        fixture.snapshot.statuses().get(shanghai.appendedVersions().getFirst().versionId()));
    assertEquals(
        AssertionStatus.CURRENT,
        fixture.snapshot.statuses().get(hangzhou.appendedVersions().getFirst().versionId()));
    assertEquals(2, fixture.snapshot.versions().size());
    assertEquals(
        interval("since 2024", "2024-01-01T00:00:00Z", null),
        hangzhou.appendedVersions().getFirst().eventTime());
  }

  @Test
  void threeCoffeePhrasesReinforceOneVersionWithoutEmbeddingAuthority() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    fixture.apply("coffee", "The user drinks coffee.", null, 1, T0);
    TransitionPlan second =
        fixture.apply(
            "coffee", "Coffee is the user's morning beverage.", null, 2, T0.plusSeconds(1));
    TransitionPlan third =
        fixture.apply("coffee", "The user likes to drink coffee.", null, 3, T0.plusSeconds(2));

    assertEquals(TransitionOperation.REINFORCE, second.operation());
    assertEquals(TransitionOperation.REINFORCE, third.operation());
    assertEquals("PARAPHRASE", second.appendedTransitions().getFirst().reason());
    assertEquals(1, fixture.snapshot.versions().size());
    assertEquals(3, fixture.snapshot.transitions().size());
  }

  @Test
  void overlappingSingleValuedIntervalsConflict() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    fixture.apply(
        "Shanghai",
        "Shanghai",
        interval("2020-2025", "2020-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
        1,
        T0);
    TransitionPlan overlap =
        fixture.apply(
            "Hangzhou",
            "Hangzhou",
            interval("2024-2026", "2024-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
            2,
            T0.plusSeconds(1));

    assertEquals(TransitionOperation.CONFLICT, overlap.operation());
    assertTrue(
        fixture.snapshot.statuses().values().stream()
            .allMatch(status -> status == AssertionStatus.CONFLICTED));
  }

  @Test
  void nonOverlappingSingleValuesCreateHistory() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    fixture.apply(
        "former",
        "former",
        interval("2020", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"),
        1,
        T0);
    fixture.apply(
        "current",
        "current",
        interval("2021", "2021-01-01T00:00:00Z", "2022-01-01T00:00:00Z"),
        2,
        T0.plusSeconds(1));

    assertEquals(1, count(fixture.snapshot, AssertionStatus.CURRENT));
    assertEquals(1, count(fixture.snapshot, AssertionStatus.HISTORICAL));
  }

  @Test
  void setValuedPredicateCoexistsWithoutTime() {
    Fixture fixture = fixture(PredicateCardinality.SET);
    fixture.apply("coffee", "coffee", null, 1, T0);
    TransitionPlan tea = fixture.apply("tea", "tea", null, 2, T0.plusSeconds(1));

    assertEquals(TransitionOperation.COEXIST, tea.operation());
    assertEquals(2, count(fixture.snapshot, AssertionStatus.CURRENT));
  }

  @Test
  void lateBackfillIsHistoricalAndDoesNotReplaceCurrent() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    TransitionPlan current =
        fixture.apply(
            "current",
            "current",
            interval("2024", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
            1,
            T0);
    TransitionPlan backfill =
        fixture.apply(
            "older",
            "older",
            interval("2020", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"),
            2,
            T0.plusSeconds(1));

    assertEquals(TransitionOperation.SUPERSEDE, backfill.operation());
    assertEquals(
        AssertionStatus.CURRENT,
        fixture.snapshot.statuses().get(current.appendedVersions().getFirst().versionId()));
    assertEquals(
        AssertionStatus.HISTORICAL,
        fixture.snapshot.statuses().get(backfill.appendedVersions().getFirst().versionId()));
  }

  @Test
  void uncertainPartialDateConflictsInsteadOfGuessing() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    fixture.apply(
        "Shanghai",
        "Shanghai",
        interval("2020", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"),
        1,
        T0);
    TemporalValidity uncertain =
        new TemporalValidity("sometime later", null, null, TemporalPrecision.UNKNOWN, 0.3);

    TransitionPlan result = fixture.apply("Hangzhou", "Hangzhou", uncertain, 2, T0.plusSeconds(1));

    assertEquals(TransitionOperation.CONFLICT, result.operation());
  }

  @Test
  void historicalOverlapConflictsOnlyWithTheOverlappedHistory() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    TransitionPlan historical =
        fixture.apply(
            "A", "A", interval("2020-2022", "2020-01-01T00:00:00Z", "2022-01-01T00:00:00Z"), 1, T0);
    TransitionPlan current =
        fixture.apply(
            "B",
            "B",
            interval("2022-2025", "2022-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
            2,
            T0.plusSeconds(1));
    TransitionPlan backfill =
        fixture.apply(
            "C",
            "C",
            interval("2021", "2021-01-01T00:00:00Z", "2021-06-01T00:00:00Z"),
            3,
            T0.plusSeconds(2));

    assertEquals(TransitionOperation.CONFLICT, backfill.operation());
    Map<AssertionVersionId, AssertionStatus> statuses = fixture.snapshot.statuses();
    assertEquals(
        AssertionStatus.CONFLICTED,
        statuses.get(historical.appendedVersions().getFirst().versionId()));
    assertEquals(
        AssertionStatus.CURRENT, statuses.get(current.appendedVersions().getFirst().versionId()));
    assertEquals(
        AssertionStatus.CONFLICTED,
        statuses.get(backfill.appendedVersions().getFirst().versionId()));
  }

  @Test
  void replayIsIgnoredWithoutAppendingOrAdvancingLock() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    MaterializeCandidateCommand command =
        fixture.command("coffee", "coffee", null, 1, T0, fixture.snapshot.lockVersion());
    TransitionPlan first = fixture.planner.planMaterialization(fixture.snapshot, command);
    fixture.snapshot = first.resultingSnapshot();

    TransitionPlan replay = fixture.planner.planMaterialization(fixture.snapshot, command);

    assertEquals(TransitionOperation.IGNORE, replay.operation());
    assertTrue(replay.replayed());
    assertSame(fixture.snapshot, replay.resultingSnapshot());
    assertTrue(replay.appendedVersions().isEmpty());
    assertTrue(replay.appendedTransitions().isEmpty());
  }

  @Test
  void correctionInvalidationAsOfAndDiffRemainAppendOnly() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    TransitionPlan created =
        fixture.apply(
            "wrong",
            "wrong",
            interval("2020", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"),
            1,
            T0);
    CorrectAssertionCommand correction =
        new CorrectAssertionCommand(
            fixture.identity,
            created.appendedVersions().getFirst().versionId(),
            new TextCandidateValue("right"),
            "right",
            null,
            interval("2020", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"),
            0.8,
            0.9,
            provenance(2, AssertionDerivationRole.CORRECTED),
            new TransitionContext(TransitionActor.USER, TransitionSource.CORRECTION, "policy-v1"),
            "user correction",
            T0.plusSeconds(10),
            fixture.snapshot.lockVersion());
    TransitionPlan corrected = fixture.planner.planCorrection(fixture.snapshot, correction);
    fixture.snapshot = corrected.resultingSnapshot();

    MemoryLineageHistory history = new MemoryLineageHistory();
    MemoryAsOfView before =
        history.asOf(
            fixture.snapshot,
            new MemoryAsOfQuery(SCOPE, fixture.identity.lineageId(), T0.plusSeconds(5)));
    MemoryDiff diff =
        history.diff(
            fixture.snapshot,
            new MemoryDiffQuery(
                SCOPE, fixture.identity.lineageId(), T0.plusSeconds(5), T0.plusSeconds(15)));

    assertEquals(AssertionStatus.CURRENT, before.statuses().values().iterator().next());
    assertEquals(1, diff.transitions().size());
    assertEquals(1, diff.appendedVersions().size());
    assertEquals(
        AssertionStatus.INVALIDATED,
        fixture.snapshot.statuses().get(created.appendedVersions().getFirst().versionId()));
    assertEquals(2, fixture.snapshot.versions().size());
  }

  @Test
  void invalidTransitionAndStaleExpectedLockFailClosed() {
    Fixture fixture = fixture(PredicateCardinality.SINGLE);
    TransitionPlan created = fixture.apply("value", "value", null, 1, T0);
    InvalidateAssertionCommand stale =
        new InvalidateAssertionCommand(
            SCOPE,
            fixture.identity.lineageId(),
            created.appendedVersions().getFirst().versionId(),
            new TransitionContext(
                TransitionActor.OPERATOR, TransitionSource.INVALIDATION, "policy-v1"),
            "invalid",
            T0.plusSeconds(1),
            0);
    assertThrows(
        OptimisticLockException.class,
        () -> fixture.planner.planInvalidation(fixture.snapshot, stale));

    InvalidateAssertionCommand valid =
        new InvalidateAssertionCommand(
            SCOPE,
            fixture.identity.lineageId(),
            created.appendedVersions().getFirst().versionId(),
            stale.transitionContext(),
            "invalid",
            T0.plusSeconds(1),
            fixture.snapshot.lockVersion());
    fixture.snapshot =
        fixture.planner.planInvalidation(fixture.snapshot, valid).resultingSnapshot();
    InvalidateAssertionCommand again =
        new InvalidateAssertionCommand(
            SCOPE,
            fixture.identity.lineageId(),
            valid.versionId(),
            valid.transitionContext(),
            "again",
            T0.plusSeconds(2),
            fixture.snapshot.lockVersion());
    assertThrows(
        InvalidTransitionException.class,
        () -> fixture.planner.planInvalidation(fixture.snapshot, again));
  }

  private static long count(MemoryLineageSnapshot snapshot, AssertionStatus status) {
    return snapshot.statuses().values().stream().filter(status::equals).count();
  }

  private static Fixture fixture(PredicateCardinality cardinality) {
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            new MemoryLineageId(new UUID(0, cardinality.ordinal() + 1L)),
            SCOPE,
            MemoryType.SEMANTIC,
            new CandidateSubject(SubjectKind.USER, null),
            "profile.home_city",
            cardinality);
    return new Fixture(
        identity,
        new TemporalTransitionPlanner(
            new NormalizedAssertionDeduplication(), new SequentialIdentifiers()),
        MemoryLineageSnapshot.empty(identity));
  }

  private static TemporalValidity interval(String text, String start, String end) {
    return new TemporalValidity(
        text,
        start == null ? null : Instant.parse(start),
        end == null ? null : Instant.parse(end),
        TemporalPrecision.DAY,
        1.0);
  }

  private static AssertionProvenance provenance(int candidateNumber, AssertionDerivationRole role) {
    return new AssertionProvenance(
        new UUID(1, candidateNumber),
        new UUID(2, candidateNumber),
        new UUID(3, candidateNumber),
        "extractor-v1",
        "prompt-v1",
        "model-v1",
        "policy-v1",
        "memory-candidate.v1",
        role,
        null);
  }

  private static final class Fixture {
    private final MemoryLineageIdentity identity;
    private final TemporalTransitionPlanner planner;
    private MemoryLineageSnapshot snapshot;

    private Fixture(
        MemoryLineageIdentity identity,
        TemporalTransitionPlanner planner,
        MemoryLineageSnapshot snapshot) {
      this.identity = identity;
      this.planner = planner;
      this.snapshot = snapshot;
    }

    private TransitionPlan apply(
        String value, String content, TemporalValidity validity, int candidate, Instant at) {
      TransitionPlan plan =
          planner.planMaterialization(
              snapshot, command(value, content, validity, candidate, at, snapshot.lockVersion()));
      snapshot = plan.resultingSnapshot();
      return plan;
    }

    private MaterializeCandidateCommand command(
        String value,
        String content,
        TemporalValidity validity,
        int candidate,
        Instant at,
        long expectedLockVersion) {
      return new MaterializeCandidateCommand(
          identity,
          new TextCandidateValue(value),
          content,
          validity,
          validity,
          0.7,
          0.95,
          provenance(candidate, AssertionDerivationRole.EXTRACTED),
          new TransitionContext(
              TransitionActor.WORKER, TransitionSource.CANDIDATE_MATERIALIZATION, "policy-v1"),
          at,
          expectedLockVersion);
    }
  }

  private static final class SequentialIdentifiers implements TemporalIdentityGenerator {
    private long assertion;
    private long transition;

    @Override
    public AssertionVersionId nextAssertionVersionId() {
      return new AssertionVersionId(new UUID(10, ++assertion));
    }

    @Override
    public StateTransitionId nextStateTransitionId() {
      return new StateTransitionId(new UUID(11, ++transition));
    }
  }
}
