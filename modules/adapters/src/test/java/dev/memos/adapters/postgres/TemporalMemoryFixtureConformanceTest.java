package dev.memos.adapters.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.memos.domain.candidate.CandidateSubject;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.candidate.TemporalPrecision;
import dev.memos.domain.candidate.TextCandidateValue;
import dev.memos.domain.temporal.AssertionDerivationRole;
import dev.memos.domain.temporal.AssertionProvenance;
import dev.memos.domain.temporal.AssertionStateTransition;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.domain.temporal.AssertionVersion;
import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.CorrectAssertionCommand;
import dev.memos.domain.temporal.InvalidateAssertionCommand;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MaterializeCandidateCommand;
import dev.memos.domain.temporal.MemoryAsOfQuery;
import dev.memos.domain.temporal.MemoryAsOfView;
import dev.memos.domain.temporal.MemoryDiff;
import dev.memos.domain.temporal.MemoryDiffQuery;
import dev.memos.domain.temporal.MemoryLineageHistory;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.MemoryLineageIdentity;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import dev.memos.domain.temporal.NormalizedAssertionDeduplication;
import dev.memos.domain.temporal.OptimisticLockException;
import dev.memos.domain.temporal.PredicateCardinality;
import dev.memos.domain.temporal.StateTransitionId;
import dev.memos.domain.temporal.TemporalIdentityGenerator;
import dev.memos.domain.temporal.TemporalTransitionPlanner;
import dev.memos.domain.temporal.TemporalValidity;
import dev.memos.domain.temporal.TransitionActor;
import dev.memos.domain.temporal.TransitionContext;
import dev.memos.domain.temporal.TransitionPlan;
import dev.memos.domain.temporal.TransitionSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TemporalMemoryFixtureConformanceTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void emitsByteStableObservedPredictionsForAllTemporalMemoryCases() throws Exception {
    Path repository = findRepositoryRoot();
    FixtureFile fixture =
        JSON.readValue(
            Files.readString(
                repository.resolve("benchmark/fixtures/temporal-memory/v1/cases.json"),
                StandardCharsets.UTF_8),
            FixtureFile.class);

    assertThat(fixture.fixture_version()).isEqualTo("temporal-memory-v1");
    assertThat(fixture.cases()).hasSize(14);

    byte[] first = runFixture(fixture);
    byte[] second = runFixture(fixture);
    assertThat(second).isEqualTo(first);

    Path output = repository.resolve("modules/adapters/target/temporal-memory/predictions.jsonl");
    Files.createDirectories(output.getParent());
    Files.write(output, first);
    assertThat(Files.readAllBytes(output)).isEqualTo(first);
    assertThat(Files.readAllLines(output, StandardCharsets.UTF_8)).hasSize(14);
  }

  private static byte[] runFixture(FixtureFile fixture) throws IOException {
    StringBuilder jsonLines = new StringBuilder();
    for (FixtureCase fixtureCase : fixture.cases()) {
      Prediction prediction =
          new Prediction(fixtureCase.case_id(), new Harness(fixtureCase).execute());
      jsonLines.append(JSON.writeValueAsString(prediction)).append('\n');
    }
    return jsonLines.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static final class Harness {
    private final Scenario scenario;
    private final Set<String> coverage;
    private final LineageScope scope;
    private final MemoryLineageIdentity identity;
    private final TemporalTransitionPlanner planner;
    private final MemoryLineageHistory history = new MemoryLineageHistory();
    private final Map<UUID, String> sourceIds = new LinkedHashMap<>();
    private final List<String> commandOutcomes = new ArrayList<>();
    private final List<String> transitionOperations = new ArrayList<>();
    private MemoryLineageSnapshot snapshot;
    private Map<AssertionVersionId, AssertionStatus> rebuiltProjection;
    private int rebuildVersionCount;
    private int rebuildTransitionCount;
    private boolean foreignScopeContentDisclosed;
    private String errorCode;
    private String foreignScopeErrorCode;

    private Harness(FixtureCase fixtureCase) {
      scenario = fixtureCase.scenario();
      coverage = Set.copyOf(fixtureCase.coverage());
      scope = scope(scenario.scope());
      identity =
          new MemoryLineageIdentity(
              new MemoryLineageId(stableUuid("lineage:" + fixtureCase.case_id())),
              scope,
              MemoryType.SEMANTIC,
              new CandidateSubject(SubjectKind.USER, null),
              "fixture.value",
              PredicateCardinality.valueOf(scenario.predicate_cardinality()));
      planner =
          new TemporalTransitionPlanner(
              new NormalizedAssertionDeduplication(), new SequentialIdentifiers());
    }

    private Map<String, Object> execute() {
      for (Command command : scenario.commands()) {
        execute(command);
      }
      return observe();
    }

    private void execute(Command command) {
      switch (command.operation()) {
        case "MATERIALIZE" -> materialize(command);
        case "CORRECT" -> correct(command);
        case "INVALIDATE" -> invalidate(command);
        case "REBUILD_CURRENT_PROJECTION" -> rebuild();
        case "INSPECT_AS_FOREIGN_SCOPE" -> inspectAsForeignScope();
        default -> throw new IllegalArgumentException("unsupported fixture operation");
      }
    }

    private void materialize(Command command) {
      TemporalValidity validity;
      try {
        validity = validity(command);
      } catch (IllegalArgumentException exception) {
        commandOutcomes.add("INVALID_REQUEST");
        transitionOperations.add("NONE");
        errorCode = "INVALID_TEMPORAL_INTERVAL";
        return;
      }
      MemoryLineageSnapshot current =
          snapshot == null ? MemoryLineageSnapshot.empty(identity) : snapshot;
      TransitionPlan plan =
          planner.planMaterialization(
              current,
              new MaterializeCandidateCommand(
                  identity,
                  new TextCandidateValue(value(command)),
                  normalizedContent(command),
                  null,
                  validity,
                  0.7,
                  0.95,
                  provenance(command, AssertionDerivationRole.EXTRACTED),
                  context(TransitionSource.CANDIDATE_MATERIALIZATION),
                  Instant.parse(command.transaction_time()),
                  current.lockVersion()));
      snapshot = plan.resultingSnapshot();
      retainSource(command);
      commandOutcomes.add(outcome(plan));
      transitionOperations.add(plan.operation().name());
    }

    private void correct(Command command) {
      if (snapshot == null) {
        throw new IllegalStateException("correction requires a lineage");
      }
      AssertionVersion incorrect = snapshot.versions().getFirst();
      try {
        TransitionPlan plan =
            planner.planCorrection(
                snapshot,
                new CorrectAssertionCommand(
                    identity,
                    incorrect.versionId(),
                    new TextCandidateValue(value(command)),
                    normalizedContent(command),
                    null,
                    validity(command),
                    0.7,
                    0.95,
                    provenance(command, AssertionDerivationRole.CORRECTED),
                    context(TransitionSource.CORRECTION),
                    command.reason() == null ? "USER_CORRECTION" : command.reason(),
                    Instant.parse(command.transaction_time()),
                    Objects.requireNonNull(command.expected_lock_version())));
        snapshot = plan.resultingSnapshot();
        if (plan.replayed()) {
          commandOutcomes.add("IDEMPOTENT_REPLAY");
          transitionOperations.add("NONE");
        } else {
          retainSource(command);
          commandOutcomes.add("APPLIED");
          transitionOperations.add(plan.operation().name());
        }
      } catch (OptimisticLockException exception) {
        commandOutcomes.add("PRECONDITION_FAILED");
        transitionOperations.add("NONE");
      }
    }

    private void invalidate(Command command) {
      if (snapshot == null) {
        throw new IllegalStateException("invalidation requires a lineage");
      }
      TransitionPlan plan =
          planner.planInvalidation(
              snapshot,
              new InvalidateAssertionCommand(
                  scope,
                  identity.lineageId(),
                  snapshot.versions().getFirst().versionId(),
                  context(TransitionSource.INVALIDATION),
                  command.reason(),
                  Instant.parse(command.transaction_time()),
                  Objects.requireNonNull(command.expected_lock_version())));
      snapshot = plan.resultingSnapshot();
      commandOutcomes.add("APPLIED");
      transitionOperations.add(plan.operation().name());
    }

    private void rebuild() {
      if (snapshot == null) {
        throw new IllegalStateException("rebuild requires a lineage");
      }
      rebuildVersionCount = snapshot.versions().size();
      rebuildTransitionCount = snapshot.transitions().size();
      rebuiltProjection =
          new MemoryLineageSnapshot(
                  snapshot.identity(),
                  snapshot.lockVersion(),
                  snapshot.versions(),
                  snapshot.transitions())
              .statuses();
      commandOutcomes.add("REBUILT");
      transitionOperations.add("REBUILD");
    }

    private void inspectAsForeignScope() {
      LineageScope foreignScope = scope(scenario.foreign_scope());
      if (snapshot == null || !snapshot.identity().scope().equals(foreignScope)) {
        commandOutcomes.add("NOT_FOUND");
        transitionOperations.add("NONE");
        foreignScopeErrorCode = "MEMORY_NOT_FOUND";
        foreignScopeContentDisclosed = false;
        return;
      }
      foreignScopeContentDisclosed = true;
      throw new AssertionError("fixture foreign scope unexpectedly matched");
    }

    private Map<String, Object> observe() {
      Map<String, Object> observed = new LinkedHashMap<>();
      observed.put("command_outcomes", List.copyOf(commandOutcomes));
      observed.put("transition_operations", List.copyOf(transitionOperations));
      observed.put("lineage_count", snapshot == null ? 0 : 1);
      observed.put("assertion_version_count", snapshot == null ? 0 : snapshot.versions().size());
      observed.put("final_lock_version", snapshot == null ? null : snapshot.lockVersion());
      observed.put("status_by_version", statusByVersion());
      observed.put("current_values", valuesWithStatus(AssertionStatus.CURRENT));

      if (coverage.contains("former") || coverage.contains("late_backfill")) {
        observed.put("former_values", valuesWithStatus(AssertionStatus.HISTORICAL));
      }
      if (coverage.contains("conflict")
          || coverage.contains("cardinality")
          || coverage.contains("non_overlap_history")) {
        observed.put("conflicted_values", valuesWithStatus(AssertionStatus.CONFLICTED));
      }
      if (coverage.contains("residence_change")) {
        observeResidenceChange(observed);
      }
      if (coverage.contains("paraphrase_dedup")
          || coverage.contains("overlapping_contradiction")
          || coverage.contains("concurrent")) {
        observed.put("provenance_source_ids", sortedRetainedSourceIds());
      }
      if (coverage.contains("late_backfill")) {
        observed.put("valid_time_order", valuesByValidTime());
        observed.put("transaction_time_order", valuesByTransactionTime());
      }
      if (coverage.contains("uncertain_date")) {
        observed.put("preserved_time", preservedTime());
      }
      if (coverage.contains("replay")) {
        observed.put("transition_count", snapshot.transitions().size());
      }
      if (coverage.contains("invalid")) {
        observed.put("error_code", errorCode);
      }
      if (coverage.contains("correction") && coverage.contains("provenance")) {
        observed.put("provenance_by_version", provenanceByVersion());
        observed.put("transition_reasons", transitionReasons());
      }
      if (coverage.contains("rebuild")) {
        observed.put(
            "projection_matches_authoritative_replay",
            rebuiltProjection.equals(snapshot.statuses()));
        observed.put(
            "rebuild_changed_authoritative_history",
            rebuildVersionCount != snapshot.versions().size()
                || rebuildTransitionCount != snapshot.transitions().size());
      }
      if (coverage.contains("invalidation")) {
        observed.put("invalidated_values", valuesWithStatus(AssertionStatus.INVALIDATED));
        observed.put("transition_reasons", transitionReasons());
      }
      if (coverage.contains("scope_isolation")) {
        observed.put("foreign_scope_error_code", foreignScopeErrorCode);
        observed.put("foreign_scope_content_disclosed", foreignScopeContentDisclosed);
      }
      return observed;
    }

    private void observeResidenceChange(Map<String, Object> observed) {
      List<Command> materializations =
          scenario.commands().stream()
              .filter(command -> command.operation().equals("MATERIALIZE"))
              .toList();
      Command replacement = materializations.get(1);
      Instant validChange = Instant.parse(replacement.valid_from());
      Instant transactionChange = Instant.parse(replacement.transaction_time());
      List<Map<String, Object>> asOf = new ArrayList<>();
      for (Instant at : List.of(validChange.minusSeconds(1), validChange, transactionChange)) {
        MemoryAsOfView view =
            history.asOf(snapshot, new MemoryAsOfQuery(scope, identity.lineageId(), at));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", at.toString());
        row.put(
            "values",
            valuesWithStatus(view.visibleVersions(), view.statuses(), AssertionStatus.CURRENT));
        asOf.add(row);
      }
      observed.put("as_of", asOf);
      observed.put("valid_change_times", List.of(validChange.toString()));
      observed.put("transaction_change_times", List.of(transactionChange.toString()));

      Instant from = transactionChange.minusSeconds(1);
      MemoryDiff diff =
          history.diff(
              snapshot, new MemoryDiffQuery(scope, identity.lineageId(), from, transactionChange));
      Map<String, Object> diffObservation = new LinkedHashMap<>();
      diffObservation.put("from_exclusive", from.toString());
      diffObservation.put("to_inclusive", transactionChange.toString());
      diffObservation.put(
          "changed_fields",
          diff.transitions().isEmpty() ? List.of() : List.of("status", "value", "validity"));
      diffObservation.put("before_value", currentValueAsOf(from));
      diffObservation.put("after_value", currentValueAsOf(transactionChange));
      observed.put("diff", diffObservation);
      observed.put("provenance_source_ids", sortedRetainedSourceIds());
    }

    private String currentValueAsOf(Instant instant) {
      MemoryAsOfView view =
          history.asOf(snapshot, new MemoryAsOfQuery(scope, identity.lineageId(), instant));
      return valuesWithStatus(view.visibleVersions(), view.statuses(), AssertionStatus.CURRENT)
          .getFirst();
    }

    private Map<String, String> statusByVersion() {
      if (snapshot == null) {
        return Map.of();
      }
      Map<String, String> statuses = new LinkedHashMap<>();
      for (AssertionVersion version : snapshot.versions()) {
        statuses.put(
            Long.toString(version.ordinal()), snapshot.statuses().get(version.versionId()).name());
      }
      return statuses;
    }

    private List<String> valuesWithStatus(AssertionStatus status) {
      return snapshot == null
          ? List.of()
          : valuesWithStatus(snapshot.versions(), snapshot.statuses(), status);
    }

    private static List<String> valuesWithStatus(
        List<AssertionVersion> versions,
        Map<AssertionVersionId, AssertionStatus> statuses,
        AssertionStatus status) {
      return versions.stream()
          .filter(version -> statuses.get(version.versionId()) == status)
          .map(Harness::textValue)
          .sorted()
          .toList();
    }

    private List<String> sortedRetainedSourceIds() {
      return sourceIds.values().stream().distinct().sorted().toList();
    }

    private List<String> valuesByValidTime() {
      return snapshot.versions().stream()
          .sorted(Comparator.comparing(version -> version.validTime().startInclusive()))
          .map(Harness::textValue)
          .toList();
    }

    private List<String> valuesByTransactionTime() {
      return snapshot.versions().stream()
          .sorted(Comparator.comparing(AssertionVersion::recordedAt))
          .map(Harness::textValue)
          .toList();
    }

    private Map<String, Object> preservedTime() {
      TemporalValidity validity = snapshot.versions().getFirst().validTime();
      Map<String, Object> preserved = new LinkedHashMap<>();
      preserved.put("original_time_text", validity.originalText());
      preserved.put("precision", validity.precision().name());
      preserved.put("temporal_confidence", validity.confidence());
      return preserved;
    }

    private Map<String, List<String>> provenanceByVersion() {
      Map<String, List<String>> provenance = new LinkedHashMap<>();
      for (AssertionVersion version : snapshot.versions()) {
        provenance.put(
            Long.toString(version.ordinal()),
            List.of(sourceIds.get(version.provenance().sourceEventId())));
      }
      return provenance;
    }

    private List<String> transitionReasons() {
      return snapshot.transitions().stream().map(AssertionStateTransition::reason).toList();
    }

    private AssertionProvenance provenance(Command command, AssertionDerivationRole role) {
      UUID sourceEventId = stableUuid("source:" + command.source_id());
      UUID candidateId = stableUuid("candidate:" + command.idempotency_key());
      return new AssertionProvenance(
          sourceEventId,
          stableUuid("run:" + command.idempotency_key()),
          candidateId,
          "fixture-extractor-v1",
          "fixture-prompt-v1",
          "fixture-model-v1",
          "temporal-transition-v1",
          "memory-candidate.v1",
          role,
          null);
    }

    private void retainSource(Command command) {
      sourceIds.put(stableUuid("source:" + command.source_id()), command.source_id());
    }

    private static TransitionContext context(TransitionSource source) {
      return new TransitionContext(TransitionActor.WORKER, source, "temporal-transition-v1");
    }

    private static String outcome(TransitionPlan plan) {
      return switch (plan.operation()) {
        case CREATE -> "APPLIED";
        case REINFORCE -> "REINFORCED";
        case SUPERSEDE ->
            plan.appendedTransitions().getFirst().reason().equals("BACKFILLED_NON_OVERLAPPING")
                ? "BACKFILLED"
                : "APPLIED";
        case COEXIST -> "COEXISTS";
        case CONFLICT -> "CONFLICT_RECORDED";
        case IGNORE -> "IDEMPOTENT_REPLAY";
        case INVALIDATE -> "APPLIED";
      };
    }

    private static TemporalValidity validity(Command command) {
      if (command.valid_from() == null && command.valid_to() == null) {
        return null;
      }
      return new TemporalValidity(
          command.original_time_text() == null
              ? "fixture valid interval"
              : command.original_time_text(),
          command.valid_from() == null ? null : Instant.parse(command.valid_from()),
          command.valid_to() == null ? null : Instant.parse(command.valid_to()),
          command.precision() == null
              ? TemporalPrecision.DAY
              : TemporalPrecision.valueOf(command.precision()),
          command.temporal_confidence() == null ? 1.0 : command.temporal_confidence());
    }

    private static String value(Command command) {
      return command.normalized_value() == null ? command.value() : command.normalized_value();
    }

    private static String normalizedContent(Command command) {
      return command.raw_text() == null ? value(command) : command.raw_text();
    }

    private static String textValue(AssertionVersion version) {
      return ((TextCandidateValue) version.value()).value();
    }
  }

  private static LineageScope scope(FixtureScope scope) {
    return new LineageScope(scope.tenant_id(), scope.user_id(), scope.agent_id());
  }

  private static UUID stableUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Path findRepositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(
          current.resolve("benchmark/fixtures/temporal-memory/v1/cases.json"))) {
        return current;
      }
      current = current.getParent();
    }
    return fail("repository root containing temporal-memory fixture was not found");
  }

  private record Prediction(String case_id, Map<String, Object> observed) {}

  private record FixtureFile(String fixture_version, List<FixtureCase> cases) {}

  private record FixtureCase(
      String case_id,
      String split,
      List<String> coverage,
      Scenario scenario,
      Map<String, Object> expected) {}

  private record Scenario(
      FixtureScope scope,
      FixtureScope foreign_scope,
      String predicate_cardinality,
      List<Command> commands) {}

  private record FixtureScope(String tenant_id, String user_id, String agent_id) {}

  private record Command(
      String operation,
      String idempotency_key,
      Long expected_lock_version,
      String transaction_time,
      String value,
      String normalized_value,
      String raw_text,
      String valid_from,
      String valid_to,
      String source_id,
      String original_time_text,
      String precision,
      Double temporal_confidence,
      String reason) {}

  private static final class SequentialIdentifiers implements TemporalIdentityGenerator {
    private long assertion;
    private long transition;

    @Override
    public AssertionVersionId nextAssertionVersionId() {
      return new AssertionVersionId(new UUID(20, ++assertion));
    }

    @Override
    public StateTransitionId nextStateTransitionId() {
      return new StateTransitionId(new UUID(21, ++transition));
    }
  }
}
