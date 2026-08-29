package dev.memos.adapters.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.memos.context.CodePointTokenCounter;
import dev.memos.context.ContextBudget;
import dev.memos.context.MemoryContextAssembler;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.retrieval.CandidateSource;
import dev.memos.retrieval.ComponentCandidate;
import dev.memos.retrieval.ComponentSignal;
import dev.memos.retrieval.DeterministicQueryGate;
import dev.memos.retrieval.EmbeddingResult;
import dev.memos.retrieval.HybridRetrievalService;
import dev.memos.retrieval.ProjectedMemory;
import dev.memos.retrieval.RerankCandidate;
import dev.memos.retrieval.RerankResult;
import dev.memos.retrieval.RetrievalMode;
import dev.memos.retrieval.RetrievalQuery;
import dev.memos.retrieval.RetrievalResult;
import dev.memos.retrieval.RetrievalTelemetry;
import dev.memos.retrieval.RuleBasedQueryIntentParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetrievalFixtureConformanceTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-08-30T01:00:00Z");
  private static final String EMBEDDING_MODEL = "fixture-embedding-v1";

  @Test
  void emitsByteStableObservationsForEveryRetrievalCase() throws Exception {
    Path repository = findRepositoryRoot();
    FixtureFile fixture =
        JSON.readValue(
            Files.readString(
                repository.resolve("benchmark/fixtures/retrieval/v1/cases.json"),
                StandardCharsets.UTF_8),
            FixtureFile.class);

    assertThat(fixture.fixture_version()).isEqualTo("retrieval-conformance-v1");
    assertThat(fixture.cases()).hasSize(6);
    byte[] first = runFixture(fixture);
    byte[] second = runFixture(fixture);
    assertThat(second).isEqualTo(first);

    Path output = repository.resolve("modules/adapters/target/retrieval/predictions.jsonl");
    Files.createDirectories(output.getParent());
    Files.write(output, first);
    assertThat(Files.readAllBytes(output)).isEqualTo(first);
    assertThat(Files.readAllLines(output, StandardCharsets.UTF_8)).hasSize(6);
  }

  private static byte[] runFixture(FixtureFile fixture) throws IOException {
    StringBuilder lines = new StringBuilder();
    for (FixtureCase fixtureCase : fixture.cases()) {
      Prediction prediction = new Harness(fixtureCase).execute();
      assertThat(prediction.observed()).isEqualTo(fixtureCase.expected());
      lines.append(JSON.writeValueAsString(prediction)).append('\n');
    }
    return lines.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static final class Harness {
    private final FixtureCase fixture;
    private final Map<String, ProjectedMemory> memories = new LinkedHashMap<>();
    private final Map<UUID, String> tokensByVersion = new LinkedHashMap<>();
    private final List<ComponentCandidate> candidates;
    private final HybridRetrievalService retrieval;
    private final MemoryContextAssembler contexts =
        new MemoryContextAssembler(new CodePointTokenCounter());

    private Harness(FixtureCase fixture) {
      this.fixture = fixture;
      for (FixtureMemory memory : fixture.memories()) {
        UUID versionId = stableUuid("version:" + fixture.case_id() + ":" + memory.id());
        memories.put(
            memory.id(),
            new ProjectedMemory(
                stableUuid("lineage:" + fixture.case_id() + ":" + memory.lineage()),
                versionId,
                MemoryType.SEMANTIC,
                SubjectKind.USER,
                null,
                memory.predicate(),
                AssertionStatus.valueOf(memory.status()),
                memory.content(),
                null,
                null,
                Instant.parse(memory.recorded_at()),
                List.of(stableUuid("source:" + fixture.case_id() + ":" + memory.id())),
                "projection-v1",
                EMBEDDING_MODEL,
                1,
                NOW));
        tokensByVersion.put(versionId, memory.id());
      }
      candidates =
          fixture.candidates().stream()
              .map(
                  candidate ->
                      new ComponentCandidate(
                          memories.get(candidate.id()),
                          new ComponentSignal(
                              CandidateSource.valueOf(candidate.source()),
                              candidate.rank(),
                              candidate.raw_score())))
              .toList();
      retrieval =
          new HybridRetrievalService(
              Clock.fixed(NOW, ZoneOffset.UTC),
              new DeterministicQueryGate(),
              new RuleBasedQueryIntentParser(),
              request -> new EmbeddingResult(List.of(1.0f), "fixture", request.modelVersion(), 1),
              query ->
                  candidates.stream()
                      .filter(candidate -> query.sources().contains(candidate.signal().source()))
                      .toList(),
              request ->
                  new RerankResult(
                      request.candidates().stream().map(RerankCandidate::versionId).toList(),
                      "fixture",
                      request.modelVersion(),
                      0),
              RetrievalTelemetry.NOOP,
              EMBEDDING_MODEL,
              "fixture-reranker-v1");
    }

    private Prediction execute() {
      RetrievalResult vector = retrieve(RetrievalMode.VECTOR_ONLY);
      RetrievalResult hybrid = retrieve(RetrievalMode.HYBRID);
      var context = contexts.assemble(hybrid.memories(), new ContextBudget(fixture.max_tokens()));
      Observed observed =
          new Observed(
              hybrid.gate().retrieve(),
              ids(vector),
              ids(hybrid),
              context.selectedVersionIds().stream().map(tokensByVersion::get).toList());
      return new Prediction(fixture.case_id(), observed);
    }

    private RetrievalResult retrieve(RetrievalMode mode) {
      return retrieval.retrieve(
          new RetrievalQuery(
              new LineageScope("fixture-tenant", "fixture-user", "fixture-agent"),
              fixture.query(),
              mode,
              fixture.limit(),
              Math.max(20, fixture.limit()),
              null,
              null,
              null,
              false,
              null));
    }

    private List<String> ids(RetrievalResult result) {
      return result.memories().stream()
          .map(memory -> tokensByVersion.get(memory.memory().versionId()))
          .toList();
    }
  }

  private static UUID stableUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Path findRepositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("mvnw"))
          && Files.isDirectory(current.resolve("benchmark/fixtures"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }

  private record FixtureFile(String fixture_version, List<FixtureCase> cases) {}

  private record FixtureCase(
      String case_id,
      String split,
      Set<String> coverage,
      String query,
      int limit,
      int max_tokens,
      List<String> relevant_ids,
      List<FixtureMemory> memories,
      List<FixtureCandidate> candidates,
      Observed expected) {}

  private record FixtureMemory(
      String id,
      String lineage,
      String status,
      String content,
      String predicate,
      String recorded_at) {}

  private record FixtureCandidate(String id, String source, int rank, double raw_score) {}

  private record Prediction(String case_id, Observed observed) {}

  private record Observed(
      boolean gate_retrieve,
      List<String> vector_top_ids,
      List<String> hybrid_top_ids,
      List<String> context_selected_ids) {}
}
