package dev.memos.retrieval;

import dev.memos.domain.temporal.AssertionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic retrieval policy over independently generated candidates. */
public final class HybridRetrievalService {
  private static final int DEFAULT_RRF_K = 60;

  private final Clock clock;
  private final QueryGate gate;
  private final QueryIntentParser intentParser;
  private final EmbeddingPort embeddingPort;
  private final RetrievalCandidateStore candidateStore;
  private final RerankerPort rerankerPort;
  private final RetrievalTelemetry telemetry;
  private final String embeddingModelVersion;
  private final String rerankerModelVersion;
  private final int rrfK;

  public HybridRetrievalService(
      Clock clock,
      QueryGate gate,
      QueryIntentParser intentParser,
      EmbeddingPort embeddingPort,
      RetrievalCandidateStore candidateStore,
      RerankerPort rerankerPort,
      RetrievalTelemetry telemetry,
      String embeddingModelVersion,
      String rerankerModelVersion) {
    this(
        clock,
        gate,
        intentParser,
        embeddingPort,
        candidateStore,
        rerankerPort,
        telemetry,
        embeddingModelVersion,
        rerankerModelVersion,
        DEFAULT_RRF_K);
  }

  public HybridRetrievalService(
      Clock clock,
      QueryGate gate,
      QueryIntentParser intentParser,
      EmbeddingPort embeddingPort,
      RetrievalCandidateStore candidateStore,
      RerankerPort rerankerPort,
      RetrievalTelemetry telemetry,
      String embeddingModelVersion,
      String rerankerModelVersion,
      int rrfK) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
    this.intentParser = Objects.requireNonNull(intentParser, "intentParser must not be null");
    this.embeddingPort = Objects.requireNonNull(embeddingPort, "embeddingPort must not be null");
    this.candidateStore = Objects.requireNonNull(candidateStore, "candidateStore must not be null");
    this.rerankerPort = Objects.requireNonNull(rerankerPort, "rerankerPort must not be null");
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    this.embeddingModelVersion = required(embeddingModelVersion, "embeddingModelVersion");
    this.rerankerModelVersion = required(rerankerModelVersion, "rerankerModelVersion");
    if (rrfK < 1 || rrfK > 10_000) {
      throw new IllegalArgumentException("rrfK must be in [1,10000]");
    }
    this.rrfK = rrfK;
  }

  public RetrievalResult retrieve(RetrievalQuery query) {
    Objects.requireNonNull(query, "query must not be null");
    long started = System.nanoTime();
    RetrievalGateDecision decision = gate.decide(query.query());
    QueryIntent intent = intentParser.parse(query.query(), query.explicitTime());
    if (!decision.retrieve()) {
      RetrievalTrace trace =
          new RetrievalTrace(
              decision.reason(),
              intent.temporal(),
              0,
              0,
              "NOT_REQUESTED",
              "not-called",
              "not-called",
              0);
      RetrievalResult result = new RetrievalResult(decision, intent, List.of(), trace);
      record(query.mode(), "GATED", started, 0);
      return result;
    }

    EmbeddingResult embedding =
        embeddingPort.embed(new EmbeddingRequest(query.query(), embeddingModelVersion));
    if (!embedding.modelVersion().equals(embeddingModelVersion)) {
      throw new IllegalStateException("embedding provider returned a different model version");
    }
    Set<CandidateSource> sources = sources(query.mode(), intent);
    List<ComponentCandidate> componentCandidates =
        List.copyOf(
            candidateStore.findCandidates(
                new CandidateStoreQuery(
                    query.scope(),
                    query.query(),
                    intent,
                    query.predicate(),
                    query.subjectLabel(),
                    query.componentLimit(),
                    sources,
                    embedding)));
    List<RankedMemory> fused = fuse(componentCandidates, intent);
    RerankOutcome reranked = rerank(query, fused);
    List<RankedMemory> selected = reranked.memories().stream().limit(query.limit()).toList();
    RetrievalTrace trace =
        new RetrievalTrace(
            decision.reason(),
            intent.temporal(),
            componentCandidates.size(),
            fused.size(),
            reranked.outcome(),
            embedding.provider(),
            embedding.modelVersion(),
            embedding.inputTokens());
    record(query.mode(), "SUCCESS", started, selected.size());
    return new RetrievalResult(decision, intent, selected, trace);
  }

  private List<RankedMemory> fuse(List<ComponentCandidate> candidates, QueryIntent intent) {
    Map<UUID, MutableFusion> byVersion = new LinkedHashMap<>();
    for (ComponentCandidate candidate : candidates) {
      if (!visible(candidate.memory(), intent)) {
        continue;
      }
      MutableFusion fusion =
          byVersion.computeIfAbsent(
              candidate.memory().versionId(), ignored -> new MutableFusion(candidate.memory()));
      fusion.add(candidate.signal(), rrfK);
    }
    return byVersion.values().stream()
        .map(MutableFusion::ranked)
        .sorted(
            Comparator.comparingDouble(RankedMemory::fusedScore)
                .reversed()
                .thenComparing(ranked -> ranked.memory().recordedAt(), Comparator.reverseOrder())
                .thenComparing(ranked -> ranked.memory().versionId()))
        .toList();
  }

  private RerankOutcome rerank(RetrievalQuery query, List<RankedMemory> fused) {
    if (!query.rerank() || fused.isEmpty()) {
      return new RerankOutcome(fused, query.rerank() ? "EMPTY" : "DISABLED");
    }
    if (!clock.instant().isBefore(query.rerankDeadline())) {
      return new RerankOutcome(fused, "DEADLINE_EXPIRED_FALLBACK");
    }
    try {
      RerankResult result =
          rerankerPort.rerank(
              new RerankRequest(
                  query.query(),
                  fused.stream()
                      .map(
                          ranked ->
                              new RerankCandidate(
                                  ranked.memory().versionId(),
                                  ranked.memory().normalizedContent(),
                                  ranked.fusedScore()))
                      .toList(),
                  rerankerModelVersion,
                  query.rerankDeadline()));
      if (!result.modelVersion().equals(rerankerModelVersion)) {
        return new RerankOutcome(fused, "MODEL_VERSION_MISMATCH_FALLBACK");
      }
      if (!clock.instant().isBefore(query.rerankDeadline())) {
        return new RerankOutcome(fused, "DEADLINE_EXCEEDED_FALLBACK");
      }
      Set<UUID> expected = new LinkedHashSet<>();
      fused.forEach(value -> expected.add(value.memory().versionId()));
      Set<UUID> observed = new LinkedHashSet<>(result.orderedVersionIds());
      if (result.orderedVersionIds().size() != observed.size() || !expected.equals(observed)) {
        return new RerankOutcome(fused, "INVALID_RESULT_FALLBACK");
      }
      Map<UUID, RankedMemory> byId = new LinkedHashMap<>();
      fused.forEach(value -> byId.put(value.memory().versionId(), value));
      List<RankedMemory> ordered = new ArrayList<>(fused.size());
      int rank = 1;
      for (UUID id : result.orderedVersionIds()) {
        RankedMemory value = byId.get(id);
        ordered.add(
            new RankedMemory(value.memory(), value.fusedScore(), value.componentSignals(), rank++));
      }
      return new RerankOutcome(List.copyOf(ordered), "APPLIED");
    } catch (RuntimeException failure) {
      return new RerankOutcome(fused, "PROVIDER_FAILURE_FALLBACK");
    }
  }

  private static Set<CandidateSource> sources(RetrievalMode mode, QueryIntent intent) {
    if (mode == RetrievalMode.VECTOR_ONLY) {
      return Set.of(CandidateSource.VECTOR);
    }
    if (intent.temporal() == TemporalQueryIntent.PRESENT && intent.targetTime() == null) {
      return Set.of(CandidateSource.VECTOR, CandidateSource.LEXICAL, CandidateSource.STRUCTURED);
    }
    return Set.of(
        CandidateSource.VECTOR,
        CandidateSource.LEXICAL,
        CandidateSource.STRUCTURED,
        CandidateSource.TEMPORAL);
  }

  private static boolean visible(ProjectedMemory memory, QueryIntent intent) {
    if (memory.status() == AssertionStatus.INVALIDATED) {
      return false;
    }
    if (intent.temporal() == TemporalQueryIntent.PRESENT) {
      return memory.status() == AssertionStatus.CURRENT
          || memory.status() == AssertionStatus.CONFLICTED;
    }
    Instant target = intent.targetTime();
    if (target == null) {
      return true;
    }
    boolean startsBefore = memory.validFrom() == null || !memory.validFrom().isAfter(target);
    boolean endsAfter = memory.validTo() == null || memory.validTo().isAfter(target);
    return startsBefore && endsAfter;
  }

  private void record(RetrievalMode mode, String outcome, long started, int selected) {
    telemetry.record(mode, outcome, Duration.ofNanos(System.nanoTime() - started), selected);
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(name + " must contain 1 to 128 characters");
    }
    return value;
  }

  private static final class MutableFusion {
    private final ProjectedMemory memory;
    private final EnumMap<CandidateSource, ComponentSignal> signals =
        new EnumMap<>(CandidateSource.class);
    private int configuredRrfK = DEFAULT_RRF_K;

    private MutableFusion(ProjectedMemory memory) {
      this.memory = memory;
    }

    private void add(ComponentSignal signal, int rrfK) {
      configuredRrfK = rrfK;
      ComponentSignal current = signals.get(signal.source());
      if (current == null || signal.rank() < current.rank()) {
        signals.put(signal.source(), signal);
      }
    }

    private RankedMemory ranked() {
      List<ComponentSignal> ordered =
          signals.values().stream().sorted(Comparator.comparing(ComponentSignal::source)).toList();
      double score =
          ordered.stream().mapToDouble(value -> 1.0d / (configuredRrfK + value.rank())).sum();
      return new RankedMemory(memory, score, ordered, null);
    }
  }

  private record RerankOutcome(List<RankedMemory> memories, String outcome) {}
}
