package dev.memos.api.http;

import dev.memos.adapters.spring.RetrievalProperties;
import dev.memos.context.ContextAssembly;
import dev.memos.context.ContextBudget;
import dev.memos.context.MemoryContextAssembler;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.governance.MemoryScope;
import dev.memos.retrieval.HybridRetrievalService;
import dev.memos.retrieval.RankedMemory;
import dev.memos.retrieval.RetrievalMode;
import dev.memos.retrieval.RetrievalQuery;
import dev.memos.retrieval.RetrievalResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/retrieval")
public final class MemoryRetrievalController {
  private static final int DEFAULT_LIMIT = 8;
  private static final int DEFAULT_COMPONENT_LIMIT = 40;
  private static final int DEFAULT_CONTEXT_TOKENS = 1_200;

  private final HybridRetrievalService retrieval;
  private final MemoryContextAssembler contexts;
  private final ScopeContextResolver scopeResolver;
  private final RetrievalProperties properties;
  private final Clock clock;

  public MemoryRetrievalController(
      HybridRetrievalService retrieval,
      MemoryContextAssembler contexts,
      ScopeContextResolver scopeResolver,
      RetrievalProperties properties,
      Clock clock) {
    this.retrieval = retrieval;
    this.contexts = contexts;
    this.scopeResolver = scopeResolver;
    this.properties = properties;
    this.clock = clock;
  }

  @PostMapping
  RetrievalResponses.Response retrieve(
      @Valid @RequestBody MemoryRetrievalRequest body, HttpServletRequest request) {
    return execute(body, request, false);
  }

  @PostMapping("/trace")
  RetrievalResponses.Response trace(
      @RequestHeader(value = "X-MemOS-Operator-Key", required = false) String operatorKey,
      @Valid @RequestBody MemoryRetrievalRequest body,
      HttpServletRequest request) {
    authorizeOperator(operatorKey);
    return execute(body, request, true);
  }

  private RetrievalResponses.Response execute(
      MemoryRetrievalRequest body, HttpServletRequest request, boolean includeTrace) {
    int limit = value(body.limit(), DEFAULT_LIMIT, 1, 50, "limit");
    int componentLimit =
        value(
            body.componentLimit(),
            Math.max(DEFAULT_COMPONENT_LIMIT, limit),
            limit,
            200,
            "componentLimit");
    int maxTokens = value(body.maxTokens(), DEFAULT_CONTEXT_TOKENS, 64, 16_384, "maxTokens");
    boolean rerank = Boolean.TRUE.equals(body.rerank()) && properties.rerankingEnabled();
    Instant deadline = rerank ? clock.instant().plus(properties.rerankerTimeout()) : null;
    RetrievalResult result =
        retrieval.retrieve(
            new RetrievalQuery(
                scope(request),
                body.query(),
                mode(body.mode()),
                limit,
                componentLimit,
                body.predicate(),
                body.subjectLabel(),
                body.at(),
                rerank,
                deadline));
    ContextAssembly context = contexts.assemble(result.memories(), new ContextBudget(maxTokens));
    return response(result, context, includeTrace);
  }

  private RetrievalResponses.Response response(
      RetrievalResult result, ContextAssembly context, boolean includeTrace) {
    return new RetrievalResponses.Response(
        new RetrievalResponses.Gate(result.gate().retrieve(), result.gate().reason()),
        new RetrievalResponses.Intent(
            result.intent().temporal().name(), result.intent().targetTime()),
        new RetrievalResponses.Context(
            context.rendered(),
            context.tokens(),
            context.tokenCounterVersion(),
            context.considered(),
            context.selected(),
            context.truncated(),
            context.selectedVersionIds().stream().map(Object::toString).toList()),
        result.memories().stream().map(memory -> memory(memory, includeTrace)).toList(),
        includeTrace ? trace(result) : null);
  }

  private static RetrievalResponses.Memory memory(RankedMemory ranked, boolean includeComponents) {
    var memory = ranked.memory();
    return new RetrievalResponses.Memory(
        memory.memoryId().toString(),
        memory.versionId().toString(),
        memory.memoryType().name(),
        memory.subjectKind().name(),
        memory.subjectLabel(),
        memory.predicate(),
        memory.status().name(),
        memory.normalizedContent(),
        memory.validFrom(),
        memory.validTo(),
        memory.recordedAt(),
        memory.sourceEventIds().stream().map(Object::toString).toList(),
        ranked.fusedScore(),
        ranked.rerankRank(),
        new RetrievalResponses.Watermark(
            memory.transitionSequence(),
            memory.projectionPolicyVersion(),
            memory.embeddingModelVersion(),
            memory.projectedAt()),
        includeComponents
            ? ranked.componentSignals().stream()
                .map(
                    signal ->
                        new RetrievalResponses.Component(
                            signal.source().name(), signal.rank(), signal.rawScore()))
                .toList()
            : List.of());
  }

  private static RetrievalResponses.Trace trace(RetrievalResult result) {
    var trace = result.trace();
    return new RetrievalResponses.Trace(
        trace.gateReason(),
        trace.temporalIntent().name(),
        trace.componentCandidateCount(),
        trace.fusedCandidateCount(),
        trace.rerankOutcome(),
        trace.embeddingProvider(),
        trace.embeddingModelVersion(),
        trace.embeddingInputTokens());
  }

  private LineageScope scope(HttpServletRequest request) {
    MemoryScope value = scopeResolver.resolve(request);
    return new LineageScope(value.tenantId(), value.userId(), value.agentId());
  }

  private void authorizeOperator(String supplied) {
    String configured = properties.operatorKey();
    if (configured == null || configured.isBlank() || supplied == null) {
      throw new OperatorAccessDeniedException();
    }
    boolean matches =
        MessageDigest.isEqual(
            configured.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    if (!matches) {
      throw new OperatorAccessDeniedException();
    }
  }

  private static RetrievalMode mode(String value) {
    if (value == null) {
      return RetrievalMode.HYBRID;
    }
    try {
      return RetrievalMode.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("mode has an unsupported value", exception);
    }
  }

  private static int value(Integer value, int defaultValue, int min, int max, String field) {
    int actual = value == null ? defaultValue : value;
    if (actual < min || actual > max) {
      throw new IllegalArgumentException(field + " must be in [" + min + "," + max + "]");
    }
    return actual;
  }
}
