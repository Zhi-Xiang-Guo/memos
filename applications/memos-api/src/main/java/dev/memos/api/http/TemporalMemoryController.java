package dev.memos.api.http;

import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.temporal.AssertionProvenance;
import dev.memos.domain.temporal.AssertionStateTransition;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.domain.temporal.AssertionVersion;
import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.EvidenceSpan;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MemoryAsOfQuery;
import dev.memos.domain.temporal.MemoryAsOfView;
import dev.memos.domain.temporal.MemoryDiff;
import dev.memos.domain.temporal.MemoryDiffQuery;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import dev.memos.domain.temporal.MemoryLineageSummary;
import dev.memos.domain.temporal.MemoryListQuery;
import dev.memos.domain.temporal.StatusChange;
import dev.memos.domain.temporal.TemporalMemoryInspection;
import dev.memos.domain.temporal.TemporalValidity;
import dev.memos.governance.MemoryScope;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/memories")
public final class TemporalMemoryController {
  private static final int DEFAULT_PAGE_SIZE = 50;

  private final TemporalMemoryInspection inspection;
  private final ScopeContextResolver scopeResolver;
  private final ObjectMapper mapper;

  public TemporalMemoryController(
      TemporalMemoryInspection inspection,
      ScopeContextResolver scopeResolver,
      ObjectMapper mapper) {
    this.inspection = inspection;
    this.scopeResolver = scopeResolver;
    this.mapper = mapper;
  }

  @GetMapping
  TemporalMemoryResponses.Page list(
      @RequestParam(required = false) String memoryType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    MemoryListQuery query =
        new MemoryListQuery(
            scope(request),
            optionalEnum(MemoryType.class, memoryType, "memoryType"),
            optionalEnum(AssertionStatus.class, status, "status"),
            cursor,
            limit);
    var page = inspection.list(query);
    return new TemporalMemoryResponses.Page(
        page.items().stream().map(this::summary).toList(), page.nextCursor());
  }

  @GetMapping("/{memoryId}")
  ResponseEntity<TemporalMemoryResponses.Inspection> inspect(
      @PathVariable UUID memoryId, HttpServletRequest request) {
    MemoryLineageSnapshot snapshot = requireSnapshot(memoryId, request);
    Map<AssertionVersionId, AssertionStatus> statuses = snapshot.statuses();
    TemporalMemoryResponses.Inspection response =
        new TemporalMemoryResponses.Inspection(
            summary(snapshot),
            snapshot.versions().stream()
                .map(version -> version(version, statuses.get(version.versionId())))
                .toList(),
            snapshot.transitions().stream().map(TemporalMemoryController::transition).toList());
    return withEtag(snapshot.lockVersion(), response);
  }

  @GetMapping("/{memoryId}/history")
  ResponseEntity<TemporalMemoryResponses.History> history(
      @PathVariable UUID memoryId, HttpServletRequest request) {
    MemoryLineageSnapshot snapshot = requireSnapshot(memoryId, request);
    Map<AssertionVersionId, AssertionStatus> statuses = snapshot.statuses();
    TemporalMemoryResponses.History response =
        new TemporalMemoryResponses.History(
            memoryId.toString(),
            snapshot.lockVersion(),
            snapshot.versions().stream()
                .map(version -> version(version, statuses.get(version.versionId())))
                .toList(),
            snapshot.transitions().stream().map(TemporalMemoryController::transition).toList());
    return withEtag(snapshot.lockVersion(), response);
  }

  @GetMapping("/{memoryId}/current")
  ResponseEntity<TemporalMemoryResponses.Current> current(
      @PathVariable UUID memoryId, HttpServletRequest request) {
    MemoryLineageSnapshot snapshot = requireSnapshot(memoryId, request);
    Map<AssertionVersionId, AssertionStatus> statuses = snapshot.statuses();
    TemporalMemoryResponses.Current response =
        new TemporalMemoryResponses.Current(
            memoryId.toString(),
            snapshot.lockVersion(),
            selected(snapshot, statuses, AssertionStatus.CURRENT),
            selected(snapshot, statuses, AssertionStatus.CONFLICTED));
    return withEtag(snapshot.lockVersion(), response);
  }

  @GetMapping("/{memoryId}/as-of")
  TemporalMemoryResponses.AsOf asOf(
      @PathVariable UUID memoryId, @RequestParam Instant at, HttpServletRequest request) {
    LineageScope scope = scope(request);
    requireSnapshot(scope, memoryId);
    MemoryAsOfView view =
        inspection.asOf(new MemoryAsOfQuery(scope, new MemoryLineageId(memoryId), at));
    return new TemporalMemoryResponses.AsOf(
        memoryId.toString(),
        at,
        view.visibleVersions().stream()
            .map(version -> version(version, view.statuses().get(version.versionId())))
            .toList());
  }

  @GetMapping("/{memoryId}/diff")
  TemporalMemoryResponses.Diff diff(
      @PathVariable UUID memoryId,
      @RequestParam Instant fromExclusive,
      @RequestParam Instant toInclusive,
      HttpServletRequest request) {
    LineageScope scope = scope(request);
    requireSnapshot(scope, memoryId);
    MemoryDiff value =
        inspection.diff(
            new MemoryDiffQuery(scope, new MemoryLineageId(memoryId), fromExclusive, toInclusive));
    return new TemporalMemoryResponses.Diff(
        memoryId.toString(),
        fromExclusive,
        toInclusive,
        value.appendedVersions().stream().map(version -> version(version, null)).toList(),
        value.transitions().stream().map(TemporalMemoryController::transition).toList());
  }

  private MemoryLineageSnapshot requireSnapshot(UUID memoryId, HttpServletRequest request) {
    return requireSnapshot(scope(request), memoryId);
  }

  private MemoryLineageSnapshot requireSnapshot(LineageScope scope, UUID memoryId) {
    return inspection
        .inspect(scope, new MemoryLineageId(memoryId))
        .orElseThrow(MemoryNotFoundException::new);
  }

  private LineageScope scope(HttpServletRequest request) {
    MemoryScope resolved = scopeResolver.resolve(request);
    return new LineageScope(resolved.tenantId(), resolved.userId(), resolved.agentId());
  }

  private TemporalMemoryResponses.Summary summary(MemoryLineageSnapshot snapshot) {
    Map<AssertionStatus, Integer> counts = new EnumMap<>(AssertionStatus.class);
    snapshot.statuses().values().forEach(status -> counts.merge(status, 1, Integer::sum));
    Instant lastTransitionAt =
        snapshot.transitions().stream()
            .map(AssertionStateTransition::occurredAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return summary(
        new MemoryLineageSummary(
            snapshot.identity(), snapshot.lockVersion(), counts, lastTransitionAt));
  }

  private TemporalMemoryResponses.Summary summary(MemoryLineageSummary value) {
    Map<String, Integer> statusCounts = new LinkedHashMap<>();
    value.statusCounts().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> statusCounts.put(entry.getKey().name(), entry.getValue()));
    return new TemporalMemoryResponses.Summary(
        value.identity().lineageId().value().toString(),
        value.identity().memoryType().name(),
        new TemporalMemoryResponses.Subject(
            value.identity().subject().kind().name(), value.identity().subject().label()),
        value.identity().predicate(),
        value.identity().cardinality().name(),
        value.lockVersion(),
        statusCounts,
        value.lastTransitionAt());
  }

  private List<TemporalMemoryResponses.Version> selected(
      MemoryLineageSnapshot snapshot,
      Map<AssertionVersionId, AssertionStatus> statuses,
      AssertionStatus wanted) {
    return snapshot.versions().stream()
        .filter(version -> statuses.get(version.versionId()) == wanted)
        .map(version -> version(version, wanted))
        .toList();
  }

  private TemporalMemoryResponses.Version version(AssertionVersion value, AssertionStatus status) {
    return new TemporalMemoryResponses.Version(
        value.versionId().value().toString(),
        value.ordinal(),
        candidateValue(value),
        value.normalizedContent(),
        validity(value.eventTime()),
        validity(value.validTime()),
        value.importance(),
        value.confidence(),
        status == null ? null : status.name(),
        provenance(value.provenance()),
        value.recordedAt());
  }

  private Object candidateValue(AssertionVersion value) {
    try {
      return mapper.readValue(value.value().canonicalJson(), Object.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "authoritative candidate value is not canonical JSON", exception);
    }
  }

  private static TemporalMemoryResponses.TemporalValidity validity(TemporalValidity value) {
    if (value == null) {
      return null;
    }
    return new TemporalMemoryResponses.TemporalValidity(
        value.originalText(),
        value.startInclusive(),
        value.endExclusive(),
        value.precision().name(),
        value.confidence());
  }

  private static TemporalMemoryResponses.Provenance provenance(AssertionProvenance value) {
    return new TemporalMemoryResponses.Provenance(
        value.sourceEventId().toString(),
        value.extractionRunId().toString(),
        value.candidateId().toString(),
        value.extractorVersion(),
        value.promptVersion(),
        value.modelVersion(),
        value.policyVersion(),
        value.schemaVersion(),
        value.derivationRole().name(),
        evidenceSpan(value.evidenceSpan()));
  }

  private static TemporalMemoryResponses.EvidenceSpan evidenceSpan(EvidenceSpan value) {
    if (value == null) {
      return null;
    }
    return new TemporalMemoryResponses.EvidenceSpan(value.startInclusive(), value.endExclusive());
  }

  private static TemporalMemoryResponses.Transition transition(AssertionStateTransition value) {
    return new TemporalMemoryResponses.Transition(
        value.transitionId().value().toString(),
        value.sequence(),
        value.operation().name(),
        value.causedByCandidateId() == null ? null : value.causedByCandidateId().toString(),
        value.relatedVersions().stream().map(id -> id.value().toString()).toList(),
        value.statusChanges().stream().map(TemporalMemoryController::statusChange).toList(),
        value.context().actor().name(),
        value.context().source().name(),
        value.context().policyVersion(),
        value.reason(),
        value.occurredAt());
  }

  private static TemporalMemoryResponses.StatusChange statusChange(StatusChange value) {
    return new TemporalMemoryResponses.StatusChange(
        value.versionId().value().toString(),
        value.fromStatus() == null ? null : value.fromStatus().name(),
        value.toStatus().name());
  }

  private static <T extends Enum<T>> T optionalEnum(Class<T> type, String value, String field) {
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " has an unsupported value", exception);
    }
  }

  private static <T> ResponseEntity<T> withEtag(long lockVersion, T body) {
    return ResponseEntity.ok().eTag(Long.toString(lockVersion)).body(body);
  }
}
