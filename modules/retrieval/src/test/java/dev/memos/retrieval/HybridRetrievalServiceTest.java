package dev.memos.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.domain.temporal.LineageScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HybridRetrievalServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
  private static final String EMBEDDING_MODEL = "embedding-v1";

  @Test
  void fusesIndependentSourcesAndFiltersHistoricalMemoryForPresentQueries() {
    ProjectedMemory multiSource = memory(AssertionStatus.CURRENT, "dark editor theme", 1);
    ProjectedMemory vectorFirst = memory(AssertionStatus.CURRENT, "concise answers", 2);
    ProjectedMemory stale = memory(AssertionStatus.HISTORICAL, "light editor theme", 3);
    List<ComponentCandidate> candidates =
        List.of(
            hit(vectorFirst, CandidateSource.VECTOR, 1, 0.95),
            hit(multiSource, CandidateSource.VECTOR, 2, 0.90),
            hit(multiSource, CandidateSource.LEXICAL, 1, 0.80),
            hit(stale, CandidateSource.LEXICAL, 2, 0.70));

    RetrievalResult result =
        service(candidates, passThrough())
            .retrieve(query("What editor theme do I use?", RetrievalMode.HYBRID, false));

    assertEquals(multiSource.versionId(), result.memories().getFirst().memory().versionId());
    assertEquals(2, result.memories().getFirst().componentSignals().size());
    assertFalse(
        result.memories().stream()
            .anyMatch(value -> value.memory().status() == AssertionStatus.HISTORICAL));
    assertEquals(4, result.trace().componentCandidateCount());
  }

  @Test
  void vectorOnlyRequestsOnlyTheSemanticCandidateGenerator() {
    AtomicReference<Set<CandidateSource>> observedSources = new AtomicReference<>();
    RetrievalCandidateStore store =
        query -> {
          observedSources.set(query.sources());
          return List.of(
              hit(
                  memory(AssertionStatus.CURRENT, "dark theme", 1),
                  CandidateSource.VECTOR,
                  1,
                  0.8));
        };

    RetrievalResult result =
        service(store, passThrough()).retrieve(query("theme", RetrievalMode.VECTOR_ONLY, false));

    assertEquals(Set.of(CandidateSource.VECTOR), observedSources.get());
    assertEquals(1, result.memories().size());
  }

  @Test
  void conversationalGateSkipsEmbeddingAndStorage() {
    AtomicInteger embeddings = new AtomicInteger();
    AtomicInteger searches = new AtomicInteger();
    HybridRetrievalService service =
        new HybridRetrievalService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new DeterministicQueryGate(),
            new RuleBasedQueryIntentParser(),
            request -> {
              embeddings.incrementAndGet();
              return embedding();
            },
            query -> {
              searches.incrementAndGet();
              return List.of();
            },
            passThrough(),
            RetrievalTelemetry.NOOP,
            EMBEDDING_MODEL,
            "reranker-v1");

    RetrievalResult result = service.retrieve(query("谢谢", RetrievalMode.HYBRID, false));

    assertFalse(result.gate().retrieve());
    assertEquals(0, embeddings.get());
    assertEquals(0, searches.get());
  }

  @Test
  void invalidRerankerOutputFallsBackToDeterministicFusion() {
    ProjectedMemory first = memory(AssertionStatus.CURRENT, "first", 1);
    ProjectedMemory second = memory(AssertionStatus.CURRENT, "second", 2);
    RerankerPort invalid =
        request ->
            new RerankResult(
                List.of(request.candidates().getFirst().versionId()),
                "broken-provider",
                request.modelVersion(),
                1);

    RetrievalResult result =
        service(
                List.of(
                    hit(first, CandidateSource.VECTOR, 1, 0.9),
                    hit(second, CandidateSource.VECTOR, 2, 0.8)),
                invalid)
            .retrieve(query("memory", RetrievalMode.HYBRID, true));

    assertEquals("INVALID_RESULT_FALLBACK", result.trace().rerankOutcome());
    assertEquals(first.versionId(), result.memories().getFirst().memory().versionId());
    assertTrue(result.memories().stream().allMatch(value -> value.rerankRank() == null));
  }

  @Test
  void rerankerModelDriftFallsBackToDeterministicFusion() {
    ProjectedMemory memory = memory(AssertionStatus.CURRENT, "first", 1);
    RerankerPort drifted =
        request ->
            new RerankResult(List.of(memory.versionId()), "test", "unexpected-model-version", 1);

    RetrievalResult result =
        service(List.of(hit(memory, CandidateSource.VECTOR, 1, 0.9)), drifted)
            .retrieve(query("memory", RetrievalMode.HYBRID, true));

    assertEquals("MODEL_VERSION_MISMATCH_FALLBACK", result.trace().rerankOutcome());
    assertEquals(memory.versionId(), result.memories().getFirst().memory().versionId());
    assertTrue(result.memories().stream().allMatch(value -> value.rerankRank() == null));
  }

  private static HybridRetrievalService service(
      List<ComponentCandidate> candidates, RerankerPort reranker) {
    return service(query -> candidates, reranker);
  }

  private static HybridRetrievalService service(
      RetrievalCandidateStore store, RerankerPort reranker) {
    return new HybridRetrievalService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        new DeterministicQueryGate(),
        new RuleBasedQueryIntentParser(),
        request -> embedding(),
        store,
        reranker,
        RetrievalTelemetry.NOOP,
        EMBEDDING_MODEL,
        "reranker-v1");
  }

  private static RerankerPort passThrough() {
    return request ->
        new RerankResult(
            request.candidates().stream().map(RerankCandidate::versionId).toList(),
            "test",
            request.modelVersion(),
            0);
  }

  private static EmbeddingResult embedding() {
    return new EmbeddingResult(List.of(1.0f, 0.0f), "test", EMBEDDING_MODEL, 1);
  }

  private static RetrievalQuery query(String text, RetrievalMode mode, boolean rerank) {
    return new RetrievalQuery(
        new LineageScope("tenant-a", "user-a", "agent-a"),
        text,
        mode,
        10,
        20,
        null,
        null,
        null,
        rerank,
        rerank ? NOW.plusSeconds(1) : null);
  }

  private static ComponentCandidate hit(
      ProjectedMemory memory, CandidateSource source, int rank, double score) {
    return new ComponentCandidate(memory, new ComponentSignal(source, rank, score));
  }

  private static ProjectedMemory memory(AssertionStatus status, String content, int identifier) {
    UUID memoryId = new UUID(0, identifier);
    UUID versionId = new UUID(1, identifier);
    return new ProjectedMemory(
        memoryId,
        versionId,
        MemoryType.SEMANTIC,
        SubjectKind.USER,
        null,
        "preference.test",
        status,
        content,
        null,
        null,
        NOW.minusSeconds(identifier),
        new ArrayList<>(List.of(new UUID(2, identifier))),
        "projection-v1",
        EMBEDDING_MODEL,
        identifier,
        NOW);
  }
}
