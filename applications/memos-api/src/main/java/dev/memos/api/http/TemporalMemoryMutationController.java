package dev.memos.api.http;

import dev.memos.domain.temporal.AssertionVersionId;
import dev.memos.domain.temporal.LineageScope;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.governance.MemoryScope;
import dev.memos.materialization.CorrectionSelection;
import dev.memos.materialization.InvalidationSelection;
import dev.memos.materialization.TemporalMemoryMutation;
import dev.memos.materialization.TemporalMutationResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/memories")
public final class TemporalMemoryMutationController {
  private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("\"(0|[1-9][0-9]*)\"");

  private final TemporalMemoryMutation mutations;
  private final ScopeContextResolver scopeResolver;
  private final Clock clock;

  public TemporalMemoryMutationController(
      TemporalMemoryMutation mutations, ScopeContextResolver scopeResolver, Clock clock) {
    this.mutations = mutations;
    this.scopeResolver = scopeResolver;
    this.clock = clock;
  }

  @PostMapping("/{memoryId}/corrections")
  ResponseEntity<TemporalMemoryResponses.Mutation> correct(
      @PathVariable UUID memoryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("If-Match") String ifMatch,
      @Valid @RequestBody TemporalMemoryCorrectionRequest body,
      HttpServletRequest request) {
    TemporalMutationResult result =
        mutations.correct(
            new CorrectionSelection(
                scope(request),
                new MemoryLineageId(memoryId),
                new AssertionVersionId(body.incorrectVersionId()),
                body.sourceEventId(),
                body.candidateId(),
                idempotencyKey,
                expectedLockVersion(ifMatch),
                body.reason(),
                traceId(),
                clock.instant()));
    return response(result);
  }

  @PostMapping("/{memoryId}/invalidations")
  ResponseEntity<TemporalMemoryResponses.Mutation> invalidate(
      @PathVariable UUID memoryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("If-Match") String ifMatch,
      @Valid @RequestBody TemporalMemoryInvalidationRequest body,
      HttpServletRequest request) {
    TemporalMutationResult result =
        mutations.invalidate(
            new InvalidationSelection(
                scope(request),
                new MemoryLineageId(memoryId),
                new AssertionVersionId(body.versionId()),
                body.sourceEventId(),
                idempotencyKey,
                expectedLockVersion(ifMatch),
                body.reason(),
                traceId(),
                clock.instant()));
    return response(result);
  }

  private LineageScope scope(HttpServletRequest request) {
    MemoryScope resolved = scopeResolver.resolve(request);
    return new LineageScope(resolved.tenantId(), resolved.userId(), resolved.agentId());
  }

  private static long expectedLockVersion(String ifMatch) {
    Matcher matcher = STRONG_NUMERIC_ETAG.matcher(ifMatch);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("If-Match must be one strong numeric entity tag");
    }
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match lock version is out of range", exception);
    }
  }

  private static String traceId() {
    String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
    if (traceId == null || traceId.isBlank()) {
      throw new IllegalStateException("request trace is unavailable");
    }
    return traceId;
  }

  private static ResponseEntity<TemporalMemoryResponses.Mutation> response(
      TemporalMutationResult result) {
    TemporalMemoryResponses.Mutation body =
        new TemporalMemoryResponses.Mutation(
            result.lineageId().value().toString(),
            result.disposition().name(),
            result.operation().name(),
            result.lockVersion(),
            result.affectedVersionIds().stream().map(id -> id.value().toString()).toList(),
            result.transitionIds().stream().map(id -> id.value().toString()).toList());
    return ResponseEntity.ok().eTag(Long.toString(result.lockVersion())).body(body);
  }
}
