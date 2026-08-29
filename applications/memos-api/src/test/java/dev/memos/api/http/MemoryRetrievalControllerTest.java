package dev.memos.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.memos.adapters.spring.RetrievalProperties;
import dev.memos.api.security.AuthenticatedActor;
import dev.memos.api.security.MemosRoles;
import dev.memos.audit.TraceAccessAuditEvent;
import dev.memos.context.CodePointTokenCounter;
import dev.memos.context.MemoryContextAssembler;
import dev.memos.domain.candidate.MemoryType;
import dev.memos.domain.candidate.SubjectKind;
import dev.memos.domain.temporal.AssertionStatus;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.governance.MemoryScope;
import dev.memos.retrieval.CandidateSource;
import dev.memos.retrieval.ComponentCandidate;
import dev.memos.retrieval.ComponentSignal;
import dev.memos.retrieval.DeterministicQueryGate;
import dev.memos.retrieval.EmbeddingResult;
import dev.memos.retrieval.HybridRetrievalService;
import dev.memos.retrieval.ProjectedMemory;
import dev.memos.retrieval.RerankCandidate;
import dev.memos.retrieval.RerankResult;
import dev.memos.retrieval.RetrievalCandidateStore;
import dev.memos.retrieval.RetrievalTelemetry;
import dev.memos.retrieval.RuleBasedQueryIntentParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;

class MemoryRetrievalControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
  private static final LineageScope SCOPE = new LineageScope("tenant-a", "user-a", "agent-a");

  private AtomicReference<LineageScope> observedScope;
  private AtomicReference<TraceAccessAuditEvent> traceAudit;
  private MemoryRetrievalController controller;

  @BeforeEach
  void setUp() {
    observedScope = new AtomicReference<>();
    traceAudit = new AtomicReference<>();
    RetrievalCandidateStore store =
        query -> {
          observedScope.set(query.scope());
          return List.of(
              new ComponentCandidate(
                  memory(), new ComponentSignal(CandidateSource.VECTOR, 1, 0.9)));
        };
    var service =
        new HybridRetrievalService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new DeterministicQueryGate(),
            new RuleBasedQueryIntentParser(),
            request -> new EmbeddingResult(List.of(1.0f), "test", request.modelVersion(), 3),
            store,
            request ->
                new RerankResult(
                    request.candidates().stream().map(RerankCandidate::versionId).toList(),
                    "test",
                    request.modelVersion(),
                    2),
            RetrievalTelemetry.NOOP,
            "embedding-v1",
            "reranker-v1");
    RetrievalProperties properties =
        new RetrievalProperties("embedding-v1", "reranker-v1", true, Duration.ofMillis(150), 60);
    controller =
        new MemoryRetrievalController(
            service,
            new MemoryContextAssembler(new CodePointTokenCounter()),
            ignored -> new MemoryScope("tenant-a", "user-a", "agent-a"),
            ignored ->
                new AuthenticatedActor(
                    new MemoryScope("tenant-a", "user-a", "agent-a"),
                    "operator-subject",
                    Set.of(MemosRoles.OPERATOR)),
            traceAudit::set,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void returnsScopedEvidenceAndBudgetedContextWithoutDiagnosticSignals() {
    RetrievalResponses.Response response =
        controller.retrieve(
            new MemoryRetrievalRequest(
                "Which editor theme do I prefer?",
                "HYBRID",
                5,
                20,
                "preference.editor.theme",
                null,
                null,
                false,
                800),
            new MockHttpServletRequest());

    assertThat(observedScope.get()).isEqualTo(SCOPE);
    assertThat(response.memories()).singleElement();
    assertThat(response.memories().getFirst().components()).isEmpty();
    assertThat(response.context().rendered()).contains("trust=\"untrusted-data\"");
    assertThat(response.trace()).isNull();
  }

  @Test
  void traceIncludesDiagnosticSignalsAfterTheSecurityFilterAuthorizesIt() {
    MemoryRetrievalRequest body =
        new MemoryRetrievalRequest("theme", null, null, null, null, null, null, false, null);

    MDC.put(TraceIdFilter.TRACE_ID_KEY, "trace-access-1");
    try {
      RetrievalResponses.Response response = controller.trace(body, new MockHttpServletRequest());
      assertThat(response.trace()).isNotNull();
      assertThat(response.memories().getFirst().components())
          .singleElement()
          .satisfies(component -> assertThat(component.source()).isEqualTo("VECTOR"));
      assertThat(traceAudit.get())
          .isEqualTo(
              new TraceAccessAuditEvent(
                  "tenant-a", "user-a", "agent-a", "operator-subject", "trace-access-1", NOW));
    } finally {
      MDC.remove(TraceIdFilter.TRACE_ID_KEY);
    }
  }

  private static ProjectedMemory memory() {
    return new ProjectedMemory(
        new UUID(1, 1),
        new UUID(2, 1),
        MemoryType.SEMANTIC,
        SubjectKind.USER,
        null,
        "preference.editor.theme",
        AssertionStatus.CURRENT,
        "The user prefers a dark editor theme.",
        null,
        null,
        NOW.minusSeconds(30),
        List.of(new UUID(3, 1)),
        "projection-v1",
        "embedding-v1",
        2,
        NOW);
  }
}
