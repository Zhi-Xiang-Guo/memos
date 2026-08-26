package dev.memos.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.domain.temporal.AssertionDerivationRole;
import dev.memos.domain.temporal.AssertionProvenance;
import dev.memos.domain.temporal.AssertionStateTransition;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.domain.temporal.AssertionVersion;
import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MemoryAsOfQuery;
import dev.memos.domain.temporal.MemoryAsOfView;
import dev.memos.domain.temporal.MemoryDiff;
import dev.memos.domain.temporal.MemoryDiffQuery;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.MemoryLineageIdentity;
import dev.memos.domain.temporal.MemoryLineagePage;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import dev.memos.domain.temporal.MemoryLineageSummary;
import dev.memos.domain.temporal.MemoryListQuery;
import dev.memos.domain.temporal.PredicateCardinality;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.StatusChange;
import dev.memos.domain.temporal.TemporalMemoryInspection;
import dev.memos.domain.temporal.TransitionActor;
import dev.memos.domain.temporal.TransitionContext;
import dev.memos.domain.temporal.TransitionOperation;
import dev.memos.domain.temporal.TransitionSource;
import dev.memos.governance.MemoryScope;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class TemporalMemoryControllerTest {
  private static final LineageScope SCOPE = new LineageScope("tenant-a", "user-a", "agent-a");
  private static final MemoryLineageId LINEAGE_ID = id(MemoryLineageId::new, 1);
  private static final AssertionVersionId VERSION_ONE = id(AssertionVersionId::new, 11);
  private static final AssertionVersionId VERSION_TWO = id(AssertionVersionId::new, 12);
  private static final Instant FIRST_TIME = Instant.parse("2026-01-02T00:00:00Z");
  private static final Instant SECOND_TIME = Instant.parse("2026-05-02T00:00:00Z");

  private FakeInspection inspection;
  private TemporalMemoryController controller;

  @BeforeEach
  void setUp() {
    inspection = new FakeInspection(snapshot());
    controller =
        new TemporalMemoryController(
            inspection,
            ignored -> new MemoryScope("tenant-a", "user-a", "agent-a"),
            JsonMapper.builder().build());
  }

  @Test
  void listCarriesHardScopeFiltersAndBoundedCursor() {
    TemporalMemoryResponses.Page response =
        controller.list("SEMANTIC", "CURRENT", "opaque-cursor", 20, request());

    assertThat(inspection.listQuery.scope()).isEqualTo(SCOPE);
    assertThat(inspection.listQuery.memoryType()).isEqualTo(MemoryType.SEMANTIC);
    assertThat(inspection.listQuery.status()).isEqualTo(AssertionStatus.CURRENT);
    assertThat(inspection.listQuery.cursor()).isEqualTo("opaque-cursor");
    assertThat(inspection.listQuery.limit()).isEqualTo(20);
    assertThat(response.nextCursor()).isEqualTo("next-cursor");
    assertThat(response.items())
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.memoryId()).isEqualTo(LINEAGE_ID.value().toString());
              assertThat(summary.statusCounts()).containsEntry("CURRENT", 1);
            });
  }

  @Test
  void inspectHistoryAndCurrentExposeVersionedStateWithEtag() {
    var inspected = controller.inspect(LINEAGE_ID.value(), request());
    var history = controller.history(LINEAGE_ID.value(), request());
    var current = controller.current(LINEAGE_ID.value(), request());

    assertThat(inspected.getHeaders().getETag()).isEqualTo("\"2\"");
    assertThat(inspected.getBody().versions())
        .extracting(TemporalMemoryResponses.Version::status)
        .containsExactly("HISTORICAL", "CURRENT");
    assertThat(inspected.getBody().versions().getFirst().value()).isEqualTo("Shanghai");
    assertThat(inspected.getBody().versions().getFirst().provenance().sourceEventId())
        .isEqualTo(uuid(101).toString());
    assertThat(history.getBody().transitions())
        .extracting(TemporalMemoryResponses.Transition::operation)
        .containsExactly("CREATE", "SUPERSEDE");
    assertThat(current.getHeaders().getETag()).isEqualTo("\"2\"");
    assertThat(current.getBody().current())
        .extracting(TemporalMemoryResponses.Version::value)
        .containsExactly("Hangzhou");
    assertThat(current.getBody().conflicted()).isEmpty();
  }

  @Test
  void asOfAndDiffUseInclusiveAndExclusiveTransactionTimes() {
    TemporalMemoryResponses.AsOf asOf = controller.asOf(LINEAGE_ID.value(), FIRST_TIME, request());
    TemporalMemoryResponses.Diff diff =
        controller.diff(LINEAGE_ID.value(), FIRST_TIME, SECOND_TIME, request());

    assertThat(inspection.asOfQuery.scope()).isEqualTo(SCOPE);
    assertThat(inspection.asOfQuery.asOfInclusive()).isEqualTo(FIRST_TIME);
    assertThat(asOf.versions())
        .extracting(TemporalMemoryResponses.Version::value)
        .containsExactly("Shanghai");
    assertThat(inspection.diffQuery.fromExclusive()).isEqualTo(FIRST_TIME);
    assertThat(inspection.diffQuery.toInclusive()).isEqualTo(SECOND_TIME);
    assertThat(diff.appendedVersions())
        .extracting(TemporalMemoryResponses.Version::value)
        .containsExactly("Hangzhou");
    assertThat(diff.transitions())
        .extracting(TemporalMemoryResponses.Transition::operation)
        .containsExactly("SUPERSEDE");
  }

  @Test
  void absentAndForeignScopedMemoryUseTheSameNotFoundException() {
    inspection.snapshot = Optional.empty();

    assertThatThrownBy(() -> controller.inspect(LINEAGE_ID.value(), request()))
        .isExactlyInstanceOf(MemoryNotFoundException.class)
        .hasMessage(null);
    assertThatThrownBy(() -> controller.asOf(LINEAGE_ID.value(), FIRST_TIME, request()))
        .isExactlyInstanceOf(MemoryNotFoundException.class)
        .hasMessage(null);
    assertThatThrownBy(
            () -> controller.diff(LINEAGE_ID.value(), FIRST_TIME, SECOND_TIME, request()))
        .isExactlyInstanceOf(MemoryNotFoundException.class)
        .hasMessage(null);
    assertThat(inspection.asOfQuery).isNull();
    assertThat(inspection.diffQuery).isNull();
  }

  @Test
  void unsupportedFilterDoesNotReflectItsValueInTheError() {
    assertThatThrownBy(() -> controller.list("PRIVATE-CONTENT", null, null, 50, request()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("memoryType has an unsupported value")
        .hasMessageNotContaining("PRIVATE-CONTENT");
  }

  private static MemoryLineageSnapshot snapshot() {
    MemoryLineageIdentity identity =
        new MemoryLineageIdentity(
            LINEAGE_ID,
            SCOPE,
            MemoryType.SEMANTIC,
            new CandidateSubject(SubjectKind.USER, null),
            "profile.residence",
            PredicateCardinality.SINGLE);
    AssertionVersion first = version(VERSION_ONE, 1, "Shanghai", 101, FIRST_TIME);
    AssertionVersion second = version(VERSION_TWO, 2, "Hangzhou", 102, SECOND_TIME);
    AssertionStateTransition created =
        new AssertionStateTransition(
            id(StateTransitionId::new, 201),
            LINEAGE_ID,
            1,
            TransitionOperation.CREATE,
            uuid(301),
            List.of(VERSION_ONE),
            List.of(new StatusChange(VERSION_ONE, null, AssertionStatus.CURRENT)),
            context(),
            "FIRST_ASSERTION",
            FIRST_TIME);
    AssertionStateTransition superseded =
        new AssertionStateTransition(
            id(StateTransitionId::new, 202),
            LINEAGE_ID,
            2,
            TransitionOperation.SUPERSEDE,
            uuid(302),
            List.of(VERSION_ONE, VERSION_TWO),
            List.of(
                new StatusChange(VERSION_ONE, AssertionStatus.CURRENT, AssertionStatus.HISTORICAL),
                new StatusChange(VERSION_TWO, null, AssertionStatus.CURRENT)),
            context(),
            "LATER_NON_OVERLAPPING",
            SECOND_TIME);
    return new MemoryLineageSnapshot(
        identity, 2, List.of(first, second), List.of(created, superseded));
  }

  private static AssertionVersion version(
      AssertionVersionId id, long ordinal, String value, long provenanceSeed, Instant recordedAt) {
    return new AssertionVersion(
        id,
        LINEAGE_ID,
        ordinal,
        new TextCandidateValue(value),
        "The user lives in " + value + ".",
        null,
        null,
        0.7,
        0.95,
        new AssertionProvenance(
            uuid(provenanceSeed),
            uuid(provenanceSeed + 100),
            uuid(provenanceSeed + 200),
            "extractor-v1",
            "prompt-v1",
            "model-v1",
            "policy-v1",
            "schema-v1",
            AssertionDerivationRole.EXTRACTED,
            null),
        recordedAt);
  }

  private static TransitionContext context() {
    return new TransitionContext(
        TransitionActor.WORKER, TransitionSource.CANDIDATE_MATERIALIZATION, "policy-v1");
  }

  private static HttpServletRequest request() {
    return new MockHttpServletRequest("GET", "/v1/memories");
  }

  private static UUID uuid(long value) {
    return new UUID(0, value);
  }

  private static <T> T id(java.util.function.Function<UUID, T> constructor, long value) {
    return constructor.apply(uuid(value));
  }

  private static final class FakeInspection implements TemporalMemoryInspection {
    private Optional<MemoryLineageSnapshot> snapshot;
    private MemoryListQuery listQuery;
    private MemoryAsOfQuery asOfQuery;
    private MemoryDiffQuery diffQuery;

    private FakeInspection(MemoryLineageSnapshot snapshot) {
      this.snapshot = Optional.of(snapshot);
    }

    @Override
    public MemoryLineagePage list(MemoryListQuery query) {
      listQuery = query;
      MemoryLineageSnapshot value = snapshot.orElseThrow();
      return new MemoryLineagePage(
          List.of(
              new MemoryLineageSummary(
                  value.identity(),
                  value.lockVersion(),
                  Map.of(AssertionStatus.HISTORICAL, 1, AssertionStatus.CURRENT, 1),
                  SECOND_TIME)),
          "next-cursor");
    }

    @Override
    public Optional<MemoryLineageSnapshot> inspect(LineageScope scope, MemoryLineageId lineageId) {
      return snapshot
          .filter(value -> value.identity().scope().equals(scope))
          .filter(value -> value.identity().lineageId().equals(lineageId));
    }

    @Override
    public MemoryAsOfView asOf(MemoryAsOfQuery query) {
      asOfQuery = query;
      MemoryLineageSnapshot value = snapshot.orElseThrow();
      return new MemoryAsOfView(
          query,
          List.of(value.versions().getFirst()),
          Map.of(VERSION_ONE, AssertionStatus.CURRENT));
    }

    @Override
    public MemoryDiff diff(MemoryDiffQuery query) {
      diffQuery = query;
      MemoryLineageSnapshot value = snapshot.orElseThrow();
      return new MemoryDiff(
          query, List.of(value.versions().get(1)), List.of(value.transitions().get(1)));
    }
  }
}
