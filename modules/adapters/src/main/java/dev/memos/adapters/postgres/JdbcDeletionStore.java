package dev.memos.adapters.postgres;

import dev.memos.governance.ClaimedDeletion;
import dev.memos.governance.DeletionOperation;
import dev.memos.governance.DeletionPolicyBasis;
import dev.memos.governance.DeletionRequest;
import dev.memos.governance.DeletionRequestDisposition;
import dev.memos.governance.DeletionRequestException;
import dev.memos.governance.DeletionRequestFailure;
import dev.memos.governance.DeletionRequestResult;
import dev.memos.governance.DeletionRequeueCommand;
import dev.memos.governance.DeletionState;
import dev.memos.governance.DeletionStepState;
import dev.memos.governance.DeletionStore;
import dev.memos.governance.DeletionStoreResult;
import dev.memos.governance.DeletionTargetType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL deletion saga authority with lease fencing and atomic erasure propagation. */
public final class JdbcDeletionStore implements DeletionStore {
  private static final String SELECT_COLUMNS =
      """
      operation_id, tenant_id, requester_subject_id, target_type, target_memory_id,
      target_user_id, policy_basis, state, source_state, authority_state,
      projection_state, job_state, attempt, max_attempts, next_attempt_at,
      error_class, requested_at, completed_at
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Supplier<UUID> auditIdentifiers;
  private final String policyVersion;

  public JdbcDeletionStore(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      Supplier<UUID> auditIdentifiers,
      String policyVersion) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    this.auditIdentifiers =
        Objects.requireNonNull(auditIdentifiers, "auditIdentifiers must not be null");
    this.policyVersion = requireText(policyVersion, "policyVersion", 128);
  }

  @Override
  public DeletionRequestResult request(DeletionRequest request, int maxAttempts) {
    Objects.requireNonNull(request, "request must not be null");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    return transactions.execute(status -> requestInTransaction(request, maxAttempts));
  }

  private DeletionRequestResult requestInTransaction(DeletionRequest request, int maxAttempts) {
    var command = request.command();
    advisoryLock(
        "deletion-idempotency/"
            + command.requesterScope().tenantId()
            + "/"
            + command.requesterSubjectId()
            + "/"
            + command.idempotencyKey());
    advisoryLock(targetLock(request));
    if (command.targetType() == DeletionTargetType.USER) {
      advisoryLock(userScopeLock(command.requesterScope().tenantId(), command.targetUserId()));
    }

    Optional<StoredRequest> replay =
        storedByIdempotency(
            command.requesterScope().tenantId(),
            command.requesterSubjectId(),
            command.idempotencyKey());
    if (replay.isPresent()) {
      if (!HexFormat.of()
          .formatHex(replay.orElseThrow().fingerprint())
          .equals(request.requestFingerprint())) {
        throw new DeletionRequestException(DeletionRequestFailure.IDEMPOTENCY_CONFLICT);
      }
      return new DeletionRequestResult(
          replay.orElseThrow().operation(), DeletionRequestDisposition.IDEMPOTENT_REPLAY);
    }

    Optional<DeletionOperation> existing = liveTarget(request);
    if (existing.isPresent()) {
      return new DeletionRequestResult(
          existing.orElseThrow(), DeletionRequestDisposition.ALREADY_REQUESTED);
    }

    if (command.targetType() == DeletionTargetType.MEMORY && !activeMemoryExists(request)) {
      throw new DeletionRequestException(DeletionRequestFailure.NOT_FOUND);
    }

    DeletionStepState sourceState =
        command.targetType() == DeletionTargetType.USER
            ? DeletionStepState.PENDING
            : DeletionStepState.NOT_APPLICABLE;
    jdbc.update(
        """
        INSERT INTO memos.deletion_request (
            operation_id, tenant_id, requester_subject_id, requester_user_id,
            requester_agent_id, requester_authority, target_type, target_memory_id,
            target_user_id, idempotency_key, request_fingerprint, policy_basis,
            policy_version, state, source_state, authority_state, projection_state,
            job_state, attempt, max_attempts, next_attempt_at, trace_id,
            requested_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 'PENDING',
                  'PENDING', 'PENDING', 0, ?, ?, ?, ?, ?)
        """,
        request.operationId(),
        command.requesterScope().tenantId(),
        command.requesterSubjectId(),
        command.requesterScope().userId(),
        command.requesterScope().agentId(),
        command.authority().name(),
        command.targetType().name(),
        command.targetMemoryId(),
        command.targetUserId(),
        command.idempotencyKey(),
        HexFormat.of().parseHex(request.requestFingerprint()),
        command.policyBasis().name(),
        policyVersion,
        sourceState.name(),
        maxAttempts,
        Timestamp.from(command.requestedAt()),
        command.traceId(),
        Timestamp.from(command.requestedAt()),
        Timestamp.from(command.requestedAt()));

    if (command.targetType() == DeletionTargetType.MEMORY) {
      prepareMemory(request);
    } else {
      prepareUser(request);
    }
    insertAudit(request, "DELETE_REQUESTED", "REQUEST_ACCEPTED", command.requestedAt());
    DeletionOperation operation =
        findOperation(command.requesterScope().tenantId(), request.operationId()).orElseThrow();
    return new DeletionRequestResult(operation, DeletionRequestDisposition.ACCEPTED);
  }

  private void prepareMemory(DeletionRequest request) {
    var command = request.command();
    int hidden =
        jdbc.update(
            """
            UPDATE memos.memory_lineage
               SET lifecycle_state = 'DELETE_REQUESTED', lock_version = lock_version + 1,
                   updated_at = clock_timestamp()
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ? AND memory_id = ?
               AND lifecycle_state = 'ACTIVE'
            """,
            command.requesterScope().tenantId(),
            command.requesterScope().userId(),
            command.requesterScope().agentId(),
            command.targetMemoryId());
    if (hidden != 1) {
      throw new DeletionRequestException(DeletionRequestFailure.NOT_FOUND);
    }
    deleteMemoryProjections(command.requesterScope().tenantId(), command.targetMemoryId());
    cancelMemoryJobs(command.requesterScope().tenantId(), command.targetMemoryId());
    completePreparedSteps(request.operationId());
  }

  private void prepareUser(DeletionRequest request) {
    var command = request.command();
    jdbc.update(
        """
        UPDATE memos.source_event
           SET deletion_state = 'DELETE_REQUESTED'
         WHERE tenant_id = ? AND user_id = ? AND deletion_state = 'ACTIVE'
        """,
        command.requesterScope().tenantId(),
        command.targetUserId());
    jdbc.update(
        """
        UPDATE memos.memory_lineage
           SET lifecycle_state = 'DELETE_REQUESTED', lock_version = lock_version + 1,
               updated_at = clock_timestamp()
         WHERE tenant_id = ? AND user_id = ? AND lifecycle_state = 'ACTIVE'
        """,
        command.requesterScope().tenantId(),
        command.targetUserId());
    jdbc.update(
        "DELETE FROM memos.memory_search_projection WHERE tenant_id = ? AND user_id = ?",
        command.requesterScope().tenantId(),
        command.targetUserId());
    jdbc.update(
        "DELETE FROM memos.memory_projection_checkpoint WHERE tenant_id = ? AND user_id = ?",
        command.requesterScope().tenantId(),
        command.targetUserId());
    cancelUserJobs(command.requesterScope().tenantId(), command.targetUserId());
    completePreparedSteps(request.operationId());
  }

  private void completePreparedSteps(UUID operationId) {
    jdbc.update(
        """
        UPDATE memos.deletion_request
           SET projection_state = 'COMPLETED', job_state = 'COMPLETED',
               updated_at = clock_timestamp()
         WHERE operation_id = ? AND state = 'PENDING'
        """,
        operationId);
  }

  @Override
  public Optional<DeletionOperation> find(
      String tenantId, String requesterSubjectId, UUID operationId) {
    requireText(tenantId, "tenantId", 128);
    requireText(requesterSubjectId, "requesterSubjectId", 200);
    Objects.requireNonNull(operationId, "operationId must not be null");
    return queryOperation(
        "tenant_id = ? AND requester_subject_id = ? AND operation_id = ?",
        tenantId,
        requesterSubjectId,
        operationId);
  }

  @Override
  public Optional<DeletionOperation> findForTenant(String tenantId, UUID operationId) {
    requireText(tenantId, "tenantId", 128);
    Objects.requireNonNull(operationId, "operationId must not be null");
    return findOperation(tenantId, operationId);
  }

  @Override
  public Optional<DeletionOperation> requeue(DeletionRequeueCommand command, int maxAttempts) {
    Objects.requireNonNull(command, "command must not be null");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    return transactions.execute(status -> requeueInTransaction(command, maxAttempts));
  }

  private Optional<DeletionOperation> requeueInTransaction(
      DeletionRequeueCommand command, int maxAttempts) {
    String tenantId = command.requesterScope().tenantId();
    advisoryLock("deletion-operation/" + tenantId + "/" + command.operationId());
    List<DeletionOperation> rows =
        jdbc.query(
            "SELECT "
                + SELECT_COLUMNS
                + " FROM memos.deletion_request WHERE tenant_id = ? AND operation_id = ? FOR UPDATE",
            JdbcDeletionStore::mapOperation,
            tenantId,
            command.operationId());
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    DeletionOperation operation = rows.getFirst();
    if (operation.state() == DeletionState.DEAD) {
      jdbc.update(
          """
          UPDATE memos.deletion_request
             SET state = 'PENDING', attempt = 0, max_attempts = ?,
                 next_attempt_at = clock_timestamp(), error_class = NULL,
                 trace_id = ?, completed_at = NULL, updated_at = clock_timestamp()
           WHERE tenant_id = ? AND operation_id = ? AND state = 'DEAD'
          """,
          maxAttempts,
          command.traceId(),
          tenantId,
          command.operationId());
      insertAudit(command, "DELETE_REQUEUED", "PRIVACY_ADMIN_REQUEUE");
    }
    return findOperation(tenantId, command.operationId());
  }

  @Override
  public int deadLetterExpiredExhausted(Instant ignoredNow) {
    return jdbc.update(
        """
        UPDATE memos.deletion_request
           SET state = 'DEAD', lease_owner = NULL, lease_token = NULL,
               lease_expires_at = NULL, next_attempt_at = NULL,
               error_class = 'LEASE_EXPIRED_ATTEMPTS_EXHAUSTED',
               completed_at = clock_timestamp(), updated_at = clock_timestamp()
         WHERE state = 'CLAIMED' AND lease_expires_at <= clock_timestamp()
           AND attempt >= max_attempts
        """);
  }

  @Override
  public List<ClaimedDeletion> claim(
      String workerId, int batchSize, Instant ignoredNow, Duration leaseDuration) {
    requireText(workerId, "workerId", 255);
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
    long leaseMilliseconds = leaseDuration.toMillis();
    if (leaseMilliseconds < 1) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    return transactions.execute(
        status ->
            jdbc.query(
                """
                WITH candidates AS (
                    SELECT operation_id
                      FROM memos.deletion_request
                     WHERE (
                         state IN ('PENDING', 'RETRY_WAIT')
                         AND next_attempt_at <= clock_timestamp()
                     ) OR (
                         state = 'CLAIMED'
                         AND lease_expires_at <= clock_timestamp()
                         AND attempt < max_attempts
                     )
                     ORDER BY COALESCE(next_attempt_at, lease_expires_at),
                              requested_at, operation_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE memos.deletion_request request
                   SET state = 'CLAIMED', attempt = request.attempt + 1,
                       next_attempt_at = NULL, lease_owner = ?,
                       lease_token = gen_random_uuid(),
                       lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                       error_class = CASE
                           WHEN request.state = 'CLAIMED' THEN 'LEASE_EXPIRED_RECLAIMED'
                           ELSE NULL
                       END,
                       updated_at = clock_timestamp()
                  FROM candidates
                 WHERE request.operation_id = candidates.operation_id
                RETURNING request.*, request.lease_token AS returned_lease_token,
                          request.lease_expires_at AS returned_lease_expires_at
                """,
                (result, row) ->
                    new ClaimedDeletion(
                        mapOperation(result, row),
                        result.getString("lease_owner"),
                        result.getObject("returned_lease_token", UUID.class),
                        instant(result, "returned_lease_expires_at"),
                        result.getString("trace_id")),
                batchSize,
                workerId,
                leaseMilliseconds));
  }

  @Override
  public DeletionStoreResult erase(ClaimedDeletion deletion, Instant completedAt) {
    Objects.requireNonNull(deletion, "deletion must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");
    try {
      return transactions.execute(status -> eraseInTransaction(deletion, completedAt));
    } catch (LeaseLostRollbackException exception) {
      return DeletionStoreResult.LEASE_LOST;
    }
  }

  private DeletionStoreResult eraseInTransaction(ClaimedDeletion deletion, Instant completedAt) {
    List<DeletionOperation> fenced =
        jdbc.query(
            "SELECT "
                + SELECT_COLUMNS
                + """
                 FROM memos.deletion_request
                 WHERE operation_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                   AND lease_token = ? AND lease_expires_at > clock_timestamp()
                 FOR UPDATE
                """,
            JdbcDeletionStore::mapOperation,
            deletion.operation().operationId(),
            deletion.workerId(),
            deletion.leaseToken());
    if (fenced.size() != 1) {
      return DeletionStoreResult.LEASE_LOST;
    }
    DeletionOperation operation = fenced.getFirst();
    jdbc.queryForObject(
        "SELECT set_config('memos.erasure_request_id', ?, true)",
        String.class,
        operation.operationId().toString());
    if (operation.targetType() == DeletionTargetType.MEMORY) {
      eraseMemory(operation);
    } else {
      eraseUser(operation);
    }
    insertAudit(
        operation, deletion.traceId(), "DELETE_COMPLETED", "ERASURE_COMPLETED", completedAt);
    int updated =
        jdbc.update(
            """
            UPDATE memos.deletion_request
               SET state = 'COMPLETED', source_state = CASE
                       WHEN target_type = 'MEMORY' THEN 'NOT_APPLICABLE' ELSE 'COMPLETED' END,
                   authority_state = 'COMPLETED', projection_state = 'COMPLETED',
                   job_state = 'COMPLETED', next_attempt_at = NULL,
                   lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                   error_class = NULL, completed_at = ?, updated_at = clock_timestamp()
             WHERE operation_id = ? AND state = 'CLAIMED' AND lease_owner = ?
               AND lease_token = ? AND lease_expires_at > clock_timestamp()
            """,
            Timestamp.from(completedAt),
            operation.operationId(),
            deletion.workerId(),
            deletion.leaseToken());
    if (updated != 1) {
      throw new LeaseLostRollbackException();
    }
    return DeletionStoreResult.UPDATED;
  }

  private void eraseMemory(DeletionOperation operation) {
    String tenantId = operation.tenantId();
    UUID memoryId = operation.targetMemoryId();
    insertMemoryTombstones(operation.operationId(), tenantId, "memory_id = ?", memoryId);
    deleteMemoryProjections(tenantId, memoryId);
    jdbc.update(
        "DELETE FROM memos.memory_current_state WHERE tenant_id = ? AND memory_id = ?",
        tenantId,
        memoryId);
    jdbc.update(
        "DELETE FROM memos.memory_mutation_request WHERE tenant_id = ? AND memory_id = ?",
        tenantId,
        memoryId);
    redactTransitions(tenantId, "memory_id = ?", memoryId);
    eraseVersions(tenantId, "memory_id = ?", memoryId);
    eraseCandidatesForMemories(tenantId, "memory_id = ?", memoryId);
    int erased =
        jdbc.update(
            """
            UPDATE memos.memory_lineage
               SET subject_label = NULL, predicate = NULL, lifecycle_state = 'ERASED',
                   lock_version = lock_version + 1, updated_at = clock_timestamp()
             WHERE tenant_id = ? AND memory_id = ? AND lifecycle_state = 'DELETE_REQUESTED'
            """,
            tenantId,
            memoryId);
    if (erased != 1) {
      throw new IllegalStateException("deletion memory authority disappeared");
    }
    cancelMemoryJobs(tenantId, memoryId);
  }

  private void eraseUser(DeletionOperation operation) {
    String tenantId = operation.tenantId();
    String userId = operation.targetUserId();
    insertSourceTombstones(operation.operationId(), tenantId, userId);
    insertMemoryTombstones(operation.operationId(), tenantId, "user_id = ?", userId);
    jdbc.update(
        "DELETE FROM memos.memory_search_projection WHERE tenant_id = ? AND user_id = ?",
        tenantId,
        userId);
    jdbc.update(
        "DELETE FROM memos.memory_projection_checkpoint WHERE tenant_id = ? AND user_id = ?",
        tenantId,
        userId);
    jdbc.update(
        """
        DELETE FROM memos.memory_current_state current
         USING memos.memory_lineage lineage
         WHERE current.tenant_id = lineage.tenant_id
           AND current.memory_id = lineage.memory_id
           AND lineage.tenant_id = ? AND lineage.user_id = ?
        """,
        tenantId,
        userId);
    jdbc.update(
        """
        DELETE FROM memos.memory_mutation_request mutation
         USING memos.memory_lineage lineage
         WHERE mutation.tenant_id = lineage.tenant_id
           AND mutation.memory_id = lineage.memory_id
           AND lineage.tenant_id = ? AND lineage.user_id = ?
        """,
        tenantId,
        userId);
    redactTransitionsForUser(tenantId, userId);
    eraseVersionsForUser(tenantId, userId);
    eraseCandidatesForUser(tenantId, userId);
    jdbc.update(
        """
        UPDATE memos.source_event
           SET payload = '{}'::jsonb, content_fingerprint = NULL,
               request_fingerprint = NULL, deletion_state = 'ERASED'
         WHERE tenant_id = ? AND user_id = ? AND deletion_state = 'DELETE_REQUESTED'
        """,
        tenantId,
        userId);
    jdbc.update(
        """
        UPDATE memos.memory_lineage
           SET subject_label = NULL, predicate = NULL, lifecycle_state = 'ERASED',
               lock_version = lock_version + 1, updated_at = clock_timestamp()
         WHERE tenant_id = ? AND user_id = ? AND lifecycle_state = 'DELETE_REQUESTED'
        """,
        tenantId,
        userId);
    cancelUserJobs(tenantId, userId);
  }

  private void insertSourceTombstones(UUID operationId, String tenantId, String userId) {
    jdbc.update(
        """
        INSERT INTO memos.erasure_tombstone (
            tenant_id, object_type, object_id, deletion_operation_id, erased_at
        )
        SELECT tenant_id, 'SOURCE_EVENT', source_event_id, ?, clock_timestamp()
          FROM memos.source_event
         WHERE tenant_id = ? AND user_id = ?
        ON CONFLICT (tenant_id, object_type, object_id) DO NOTHING
        """,
        operationId,
        tenantId,
        userId);
  }

  private void insertMemoryTombstones(
      UUID operationId, String tenantId, String predicate, Object target) {
    jdbc.update(
        """
        INSERT INTO memos.erasure_tombstone (
            tenant_id, object_type, object_id, deletion_operation_id, erased_at
        )
        SELECT tenant_id, 'MEMORY_LINEAGE', memory_id, ?, clock_timestamp()
          FROM memos.memory_lineage
         WHERE tenant_id = ? AND
        """
            + predicate
            + " ON CONFLICT (tenant_id, object_type, object_id) DO NOTHING",
        operationId,
        tenantId,
        target);
  }

  private void eraseVersions(String tenantId, String predicate, Object target) {
    jdbc.update(
        """
        UPDATE memos.memory_version
           SET content_state = 'ERASED', value_json = NULL, normalized_content = NULL,
               importance = NULL, confidence = NULL, event_time_original = NULL,
               event_time_start = NULL, event_time_end = NULL, event_time_precision = NULL,
               event_time_confidence = NULL, valid_time_original = NULL,
               valid_time_start = NULL, valid_time_end = NULL, valid_time_precision = NULL,
               valid_time_confidence = NULL, content_fingerprint = NULL
         WHERE tenant_id = ? AND content_state = 'AVAILABLE' AND
        """
            + predicate,
        tenantId,
        target);
  }

  private void eraseVersionsForUser(String tenantId, String userId) {
    jdbc.update(
        """
        UPDATE memos.memory_version version
           SET content_state = 'ERASED', value_json = NULL, normalized_content = NULL,
               importance = NULL, confidence = NULL, event_time_original = NULL,
               event_time_start = NULL, event_time_end = NULL, event_time_precision = NULL,
               event_time_confidence = NULL, valid_time_original = NULL,
               valid_time_start = NULL, valid_time_end = NULL, valid_time_precision = NULL,
               valid_time_confidence = NULL, content_fingerprint = NULL
          FROM memos.memory_lineage lineage
         WHERE version.tenant_id = lineage.tenant_id
           AND version.memory_id = lineage.memory_id
           AND lineage.tenant_id = ? AND lineage.user_id = ?
           AND version.content_state = 'AVAILABLE'
        """,
        tenantId,
        userId);
  }

  private void eraseCandidatesForMemories(String tenantId, String predicate, Object target) {
    jdbc.update(
        """
        UPDATE memos.memory_candidate candidate
           SET proposed_decision = NULL, subject_kind = NULL, subject_label = NULL,
               predicate = NULL, value_json = NULL, normalized_content = NULL,
               memory_type = NULL, event_time_json = NULL, valid_interval_json = NULL,
               importance = NULL, confidence = NULL, relation_hints_json = NULL,
               content_fingerprint = NULL, content_state = 'ERASED'
         WHERE candidate.tenant_id = ? AND candidate.content_state = 'AVAILABLE'
           AND candidate.candidate_id IN (
               SELECT version.candidate_id
                 FROM memos.memory_version version
                WHERE version.tenant_id = candidate.tenant_id AND
        """
            + predicate
            + ")",
        tenantId,
        target);
  }

  private void eraseCandidatesForUser(String tenantId, String userId) {
    jdbc.update(
        """
        UPDATE memos.memory_candidate candidate
           SET proposed_decision = NULL, subject_kind = NULL, subject_label = NULL,
               predicate = NULL, value_json = NULL, normalized_content = NULL,
               memory_type = NULL, event_time_json = NULL, valid_interval_json = NULL,
               importance = NULL, confidence = NULL, relation_hints_json = NULL,
               content_fingerprint = NULL, content_state = 'ERASED'
         WHERE candidate.tenant_id = ? AND candidate.content_state = 'AVAILABLE'
           AND EXISTS (
               SELECT 1 FROM memos.source_event source
                WHERE source.tenant_id = candidate.tenant_id
                  AND source.source_event_id = candidate.source_event_id
                  AND source.user_id = ?
           )
        """,
        tenantId,
        userId);
  }

  private void redactTransitions(String tenantId, String predicate, Object target) {
    jdbc.update(
        "UPDATE memos.memory_state_transition SET reason = 'ERASED', actor_id = 'ERASED' "
            + "WHERE tenant_id = ? AND (reason <> 'ERASED' OR actor_id <> 'ERASED') AND "
            + predicate,
        tenantId,
        target);
  }

  private void redactTransitionsForUser(String tenantId, String userId) {
    jdbc.update(
        """
        UPDATE memos.memory_state_transition transition
           SET reason = 'ERASED', actor_id = 'ERASED'
          FROM memos.memory_lineage lineage
         WHERE transition.tenant_id = lineage.tenant_id
           AND transition.memory_id = lineage.memory_id
           AND lineage.tenant_id = ? AND lineage.user_id = ?
           AND (transition.reason <> 'ERASED' OR transition.actor_id <> 'ERASED')
        """,
        tenantId,
        userId);
  }

  private void deleteMemoryProjections(String tenantId, UUID memoryId) {
    jdbc.update(
        "DELETE FROM memos.memory_search_projection WHERE tenant_id = ? AND memory_id = ?",
        tenantId,
        memoryId);
    jdbc.update(
        "DELETE FROM memos.memory_projection_checkpoint WHERE tenant_id = ? AND memory_id = ?",
        tenantId,
        memoryId);
  }

  private void cancelMemoryJobs(String tenantId, UUID memoryId) {
    jdbc.update(
        """
        UPDATE memos.outbox_job job
           SET state = 'DEAD', next_attempt_at = NULL, lease_owner = NULL,
               lease_token = NULL, lease_expires_at = NULL,
               error_class = 'GOVERNED_ERASURE', completed_at = clock_timestamp(),
               updated_at = clock_timestamp()
         WHERE job.tenant_id = ? AND job.state <> 'SUCCEEDED'
           AND job.aggregate_type = 'MEMORY_TRANSITION'
           AND EXISTS (
               SELECT 1 FROM memos.memory_state_transition transition
                WHERE transition.tenant_id = job.tenant_id
                  AND transition.transition_id = job.aggregate_id
                  AND transition.memory_id = ?
           )
        """,
        tenantId,
        memoryId);
  }

  private void cancelUserJobs(String tenantId, String userId) {
    jdbc.update(
        """
        UPDATE memos.outbox_job job
           SET state = 'DEAD', next_attempt_at = NULL, lease_owner = NULL,
               lease_token = NULL, lease_expires_at = NULL,
               error_class = 'GOVERNED_ERASURE', completed_at = clock_timestamp(),
               updated_at = clock_timestamp()
         WHERE job.tenant_id = ? AND job.state <> 'SUCCEEDED'
           AND EXISTS (
               SELECT 1 FROM memos.source_event source
                WHERE source.tenant_id = job.tenant_id
                  AND source.source_event_id = job.source_event_id
                  AND source.user_id = ?
           )
        """,
        tenantId,
        userId);
  }

  @Override
  public DeletionStoreResult scheduleRetry(
      ClaimedDeletion deletion,
      String errorClass,
      Instant nextAttemptAt,
      Instant ignoredUpdatedAt) {
    requireText(errorClass, "errorClass", 128);
    int updated =
        jdbc.update(
            """
            UPDATE memos.deletion_request
               SET state = 'RETRY_WAIT', next_attempt_at = ?, lease_owner = NULL,
                   lease_token = NULL, lease_expires_at = NULL, error_class = ?,
                   completed_at = NULL, updated_at = clock_timestamp()
             WHERE operation_id = ? AND state = 'CLAIMED' AND lease_owner = ?
               AND lease_token = ? AND lease_expires_at > clock_timestamp()
            """,
            Timestamp.from(nextAttemptAt),
            errorClass,
            deletion.operation().operationId(),
            deletion.workerId(),
            deletion.leaseToken());
    return result(updated);
  }

  @Override
  public DeletionStoreResult markDead(
      ClaimedDeletion deletion, String errorClass, Instant completedAt) {
    requireText(errorClass, "errorClass", 128);
    int updated =
        jdbc.update(
            """
            UPDATE memos.deletion_request
               SET state = 'DEAD', next_attempt_at = NULL, lease_owner = NULL,
                   lease_token = NULL, lease_expires_at = NULL, error_class = ?,
                   completed_at = ?, updated_at = clock_timestamp()
             WHERE operation_id = ? AND state = 'CLAIMED' AND lease_owner = ?
               AND lease_token = ? AND lease_expires_at > clock_timestamp()
            """,
            errorClass,
            Timestamp.from(completedAt),
            deletion.operation().operationId(),
            deletion.workerId(),
            deletion.leaseToken());
    return result(updated);
  }

  private boolean activeMemoryExists(DeletionRequest request) {
    var command = request.command();
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM memos.memory_lineage
             WHERE tenant_id = ? AND user_id = ? AND agent_id = ? AND memory_id = ?
               AND lifecycle_state = 'ACTIVE'
            """,
            Integer.class,
            command.requesterScope().tenantId(),
            command.requesterScope().userId(),
            command.requesterScope().agentId(),
            command.targetMemoryId());
    return count != null && count == 1;
  }

  private Optional<StoredRequest> storedByIdempotency(
      String tenantId, String requesterSubjectId, String idempotencyKey) {
    List<StoredRequest> rows =
        jdbc.query(
            "SELECT "
                + SELECT_COLUMNS
                + """
                , request_fingerprint FROM memos.deletion_request
                 WHERE tenant_id = ? AND requester_subject_id = ? AND idempotency_key = ?
                """,
            (result, row) ->
                new StoredRequest(
                    mapOperation(result, row), result.getBytes("request_fingerprint")),
            tenantId,
            requesterSubjectId,
            idempotencyKey);
    return rows.stream().findFirst();
  }

  private Optional<DeletionOperation> liveTarget(DeletionRequest request) {
    var command = request.command();
    return command.targetType() == DeletionTargetType.MEMORY
        ? queryOperation(
            "tenant_id = ? AND target_type = 'MEMORY' AND target_memory_id = ?",
            command.requesterScope().tenantId(),
            command.targetMemoryId())
        : queryOperation(
            "tenant_id = ? AND target_type = 'USER' AND target_user_id = ?",
            command.requesterScope().tenantId(),
            command.targetUserId());
  }

  private Optional<DeletionOperation> findOperation(String tenantId, UUID operationId) {
    return queryOperation("tenant_id = ? AND operation_id = ?", tenantId, operationId);
  }

  private Optional<DeletionOperation> queryOperation(String predicate, Object... arguments) {
    List<DeletionOperation> rows =
        jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM memos.deletion_request WHERE " + predicate,
            JdbcDeletionStore::mapOperation,
            arguments);
    if (rows.size() > 1) {
      throw new IllegalStateException("deletion operation identity invariant violated");
    }
    return rows.stream().findFirst();
  }

  private void insertAudit(
      DeletionRequest request, String action, String reasonCode, Instant occurredAt) {
    var command = request.command();
    insertAudit(
        request.operationId(),
        command.requesterScope().tenantId(),
        command.requesterScope().userId(),
        command.requesterScope().agentId(),
        command.authority() == dev.memos.governance.DeletionAuthority.PRIVACY_ADMIN
            ? "OPERATOR"
            : "USER",
        command.requesterSubjectId(),
        command.traceId(),
        action,
        reasonCode,
        occurredAt);
  }

  private void insertAudit(
      DeletionOperation operation,
      String traceId,
      String action,
      String reasonCode,
      Instant occurredAt) {
    List<Requester> requester =
        jdbc.query(
            """
            SELECT requester_user_id, requester_agent_id, requester_authority,
                   requester_subject_id
              FROM memos.deletion_request WHERE operation_id = ?
            """,
            (result, row) ->
                new Requester(
                    result.getString("requester_user_id"),
                    result.getString("requester_agent_id"),
                    result.getString("requester_authority"),
                    result.getString("requester_subject_id")),
            operation.operationId());
    Requester actor = requester.getFirst();
    insertAudit(
        operation.operationId(),
        operation.tenantId(),
        actor.userId(),
        actor.agentId(),
        "PRIVACY_ADMIN".equals(actor.authority()) ? "OPERATOR" : "USER",
        actor.subjectId(),
        traceId,
        action,
        reasonCode,
        occurredAt);
  }

  private void insertAudit(DeletionRequeueCommand command, String action, String reasonCode) {
    insertAudit(
        command.operationId(),
        command.requesterScope().tenantId(),
        command.requesterScope().userId(),
        command.requesterScope().agentId(),
        "OPERATOR",
        command.requesterSubjectId(),
        command.traceId(),
        action,
        reasonCode,
        command.requestedAt());
  }

  private void insertAudit(
      UUID operationId,
      String tenantId,
      String userId,
      String agentId,
      String actorType,
      String actorId,
      String traceId,
      String action,
      String reasonCode,
      Instant occurredAt) {
    UUID auditId =
        Objects.requireNonNull(auditIdentifiers.get(), "generated audit ID must not be null");
    jdbc.update(
        """
        INSERT INTO memos.audit_event (
            audit_event_id, tenant_id, user_id, agent_id, actor_type, actor_id,
            action, target_type, target_id, outcome, reason_code, policy_version,
            trace_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'DELETION_OPERATION', ?, 'SUCCEEDED', ?, ?, ?, ?)
        ON CONFLICT (audit_event_id) DO NOTHING
        """,
        auditId,
        tenantId,
        userId,
        agentId,
        actorType,
        actorId,
        action,
        operationId,
        reasonCode,
        policyVersion,
        traceId,
        Timestamp.from(occurredAt));
  }

  private void advisoryLock(String key) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", result -> null, key);
  }

  private static String targetLock(DeletionRequest request) {
    var command = request.command();
    Object target =
        command.targetType() == DeletionTargetType.MEMORY
            ? command.targetMemoryId()
            : command.targetUserId();
    return "deletion-target/"
        + command.requesterScope().tenantId()
        + "/"
        + command.targetType()
        + "/"
        + target;
  }

  public static String userScopeLock(String tenantId, String userId) {
    return "user-scope/" + tenantId + "/" + userId;
  }

  private static DeletionOperation mapOperation(ResultSet result, int ignoredRow)
      throws SQLException {
    return new DeletionOperation(
        result.getObject("operation_id", UUID.class),
        result.getString("tenant_id"),
        result.getString("requester_subject_id"),
        DeletionTargetType.valueOf(result.getString("target_type")),
        result.getObject("target_memory_id", UUID.class),
        result.getString("target_user_id"),
        DeletionPolicyBasis.valueOf(result.getString("policy_basis")),
        DeletionState.valueOf(result.getString("state")),
        DeletionStepState.valueOf(result.getString("source_state")),
        DeletionStepState.valueOf(result.getString("authority_state")),
        DeletionStepState.valueOf(result.getString("projection_state")),
        DeletionStepState.valueOf(result.getString("job_state")),
        result.getInt("attempt"),
        result.getInt("max_attempts"),
        nullableInstant(result, "next_attempt_at"),
        result.getString("error_class"),
        instant(result, "requested_at"),
        nullableInstant(result, "completed_at"));
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    return result.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static DeletionStoreResult result(int updated) {
    return updated == 1 ? DeletionStoreResult.UPDATED : DeletionStoreResult.LEASE_LOST;
  }

  private static String requireText(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field + " must not be null");
    if (value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be non-blank and bounded");
    }
    return value;
  }

  private record StoredRequest(DeletionOperation operation, byte[] fingerprint) {}

  private record Requester(String userId, String agentId, String authority, String subjectId) {}

  private static final class LeaseLostRollbackException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
