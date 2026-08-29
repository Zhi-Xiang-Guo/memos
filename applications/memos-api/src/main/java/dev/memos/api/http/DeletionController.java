package dev.memos.api.http;

import dev.memos.api.security.ActorContextResolver;
import dev.memos.api.security.AuthenticatedActor;
import dev.memos.api.security.MemosRoles;
import dev.memos.governance.DeletionAuthority;
import dev.memos.governance.DeletionOperation;
import dev.memos.governance.DeletionPolicyBasis;
import dev.memos.governance.DeletionRequestCommand;
import dev.memos.governance.DeletionRequestDisposition;
import dev.memos.governance.DeletionRequestResult;
import dev.memos.governance.DeletionRequeueCommand;
import dev.memos.governance.DeletionTargetType;
import dev.memos.governance.GovernedDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public final class DeletionController {
  private final GovernedDeletionService deletions;
  private final ActorContextResolver actors;
  private final Clock clock;

  public DeletionController(
      GovernedDeletionService deletions, ActorContextResolver actors, Clock clock) {
    this.deletions = deletions;
    this.actors = actors;
    this.clock = clock;
  }

  @PostMapping("/deletions/memories/{memoryId}")
  ResponseEntity<DeletionOperationResponse> deleteMemory(
      @PathVariable UUID memoryId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody DeletionRequestBody body,
      HttpServletRequest request) {
    AuthenticatedActor actor = requireRole(actors.resolveActor(request), MemosRoles.USER);
    DeletionRequestResult result =
        deletions.request(
            new DeletionRequestCommand(
                actor.scope(),
                actor.subjectId(),
                DeletionAuthority.SELF_SERVICE,
                DeletionTargetType.MEMORY,
                memoryId,
                null,
                idempotencyKey,
                basis(body.policyBasis()),
                traceId(),
                clock.instant()));
    return requested(result);
  }

  @PostMapping("/admin/deletions/users/{userId}")
  ResponseEntity<DeletionOperationResponse> deleteUser(
      @PathVariable String userId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody DeletionRequestBody body,
      HttpServletRequest request) {
    AuthenticatedActor actor = requireRole(actors.resolveActor(request), MemosRoles.PRIVACY_ADMIN);
    DeletionRequestResult result =
        deletions.request(
            new DeletionRequestCommand(
                actor.scope(),
                actor.subjectId(),
                DeletionAuthority.PRIVACY_ADMIN,
                DeletionTargetType.USER,
                null,
                userId,
                idempotencyKey,
                basis(body.policyBasis()),
                traceId(),
                clock.instant()));
    return requested(result);
  }

  @GetMapping("/deletions/{operationId}")
  DeletionOperationResponse status(@PathVariable UUID operationId, HttpServletRequest request) {
    AuthenticatedActor actor = actors.resolveActor(request);
    return deletions
        .find(actor.scope().tenantId(), actor.subjectId(), operationId)
        .map(operation -> response(operation, null))
        .orElseThrow(DeletionNotFoundException::new);
  }

  @GetMapping("/admin/deletions/{operationId}")
  DeletionOperationResponse adminStatus(
      @PathVariable UUID operationId, HttpServletRequest request) {
    AuthenticatedActor actor = requireRole(actors.resolveActor(request), MemosRoles.PRIVACY_ADMIN);
    return deletions
        .findForTenant(actor.scope().tenantId(), operationId)
        .map(operation -> response(operation, null))
        .orElseThrow(DeletionNotFoundException::new);
  }

  @PostMapping("/admin/deletions/{operationId}/requeue")
  DeletionOperationResponse requeue(@PathVariable UUID operationId, HttpServletRequest request) {
    AuthenticatedActor actor = requireRole(actors.resolveActor(request), MemosRoles.PRIVACY_ADMIN);
    return deletions
        .requeue(
            new DeletionRequeueCommand(
                actor.scope(),
                actor.subjectId(),
                DeletionAuthority.PRIVACY_ADMIN,
                operationId,
                traceId(),
                clock.instant()))
        .map(operation -> response(operation, null))
        .orElseThrow(DeletionNotFoundException::new);
  }

  private static ResponseEntity<DeletionOperationResponse> requested(DeletionRequestResult result) {
    HttpStatus status =
        result.disposition() == DeletionRequestDisposition.ACCEPTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    return ResponseEntity.status(status)
        .location(URI.create("/v1/deletions/" + result.operation().operationId()))
        .body(response(result.operation(), result.disposition()));
  }

  private static DeletionOperationResponse response(
      DeletionOperation operation, DeletionRequestDisposition disposition) {
    return new DeletionOperationResponse(
        operation.operationId().toString(),
        operation.targetType().name(),
        operation.state().name(),
        disposition == null ? null : disposition.name(),
        new DeletionOperationResponse.Steps(
            operation.sourceState().name(),
            operation.authorityState().name(),
            operation.projectionState().name(),
            operation.jobState().name()),
        operation.attempt(),
        operation.maxAttempts(),
        operation.nextAttemptAt(),
        operation.errorClass(),
        operation.requestedAt(),
        operation.completedAt());
  }

  private static AuthenticatedActor requireRole(AuthenticatedActor actor, String role) {
    if (!actor.hasRole(role)) {
      throw new AccessDeniedException("required role is missing");
    }
    return actor;
  }

  private static DeletionPolicyBasis basis(String value) {
    return DeletionPolicyBasis.valueOf(value);
  }

  private static String traceId() {
    String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
    if (traceId == null || traceId.isBlank()) {
      throw new IllegalStateException("request trace is unavailable");
    }
    return traceId;
  }
}
