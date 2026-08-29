ALTER TABLE memos.source_event
    ALTER COLUMN request_fingerprint DROP NOT NULL;

ALTER TABLE memos.source_event
    ADD CONSTRAINT source_event_erased_content_ck CHECK (
        deletion_state <> 'ERASED'
        OR (
            payload = '{}'::jsonb
            AND content_fingerprint IS NULL
            AND request_fingerprint IS NULL
        )
    );

ALTER TABLE memos.memory_lineage
    DROP CONSTRAINT memory_lineage_scope_identity_uk,
    DROP CONSTRAINT memory_lineage_lifecycle_ck,
    ALTER COLUMN predicate DROP NOT NULL;

ALTER TABLE memos.memory_lineage
    ADD CONSTRAINT memory_lineage_lifecycle_ck CHECK (
        lifecycle_state IN ('ACTIVE', 'DELETE_REQUESTED', 'ERASED')
    ),
    ADD CONSTRAINT memory_lineage_erased_identity_ck CHECK (
        (
            lifecycle_state IN ('ACTIVE', 'DELETE_REQUESTED')
            AND predicate IS NOT NULL
        )
        OR (
            lifecycle_state = 'ERASED'
            AND predicate IS NULL
            AND subject_label IS NULL
        )
    );

CREATE UNIQUE INDEX memory_lineage_retained_identity_uk
    ON memos.memory_lineage (
        tenant_id, user_id, agent_id, memory_type, subject_kind, subject_label, predicate
    ) NULLS NOT DISTINCT
    WHERE lifecycle_state IN ('ACTIVE', 'DELETE_REQUESTED');

CREATE TABLE memos.deletion_request (
    operation_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    requester_subject_id VARCHAR(200) NOT NULL,
    requester_user_id VARCHAR(128) NOT NULL,
    requester_agent_id VARCHAR(128) NOT NULL,
    requester_authority VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_memory_id UUID,
    target_user_id VARCHAR(128),
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    policy_basis VARCHAR(64) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    source_state VARCHAR(32) NOT NULL,
    authority_state VARCHAR(32) NOT NULL,
    projection_state VARCHAR(32) NOT NULL,
    job_state VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(255),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    error_class VARCHAR(128),
    trace_id VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT deletion_request_pk PRIMARY KEY (operation_id),
    CONSTRAINT deletion_request_tenant_operation_uk UNIQUE (tenant_id, operation_id),
    CONSTRAINT deletion_request_idempotency_uk UNIQUE (
        tenant_id, requester_subject_id, idempotency_key
    ),
    CONSTRAINT deletion_request_scope_ck CHECK (
        btrim(tenant_id) <> ''
        AND btrim(requester_subject_id) <> ''
        AND btrim(requester_user_id) <> ''
        AND btrim(requester_agent_id) <> ''
    ),
    CONSTRAINT deletion_request_authority_ck CHECK (
        requester_authority IN ('SELF_SERVICE', 'PRIVACY_ADMIN')
    ),
    CONSTRAINT deletion_request_target_ck CHECK (
        (
            target_type = 'MEMORY'
            AND requester_authority = 'SELF_SERVICE'
            AND target_memory_id IS NOT NULL
            AND target_user_id IS NULL
            AND source_state = 'NOT_APPLICABLE'
        )
        OR (
            target_type = 'USER'
            AND requester_authority = 'PRIVACY_ADMIN'
            AND target_memory_id IS NULL
            AND target_user_id IS NOT NULL
            AND btrim(target_user_id) <> ''
        )
    ),
    CONSTRAINT deletion_request_metadata_ck CHECK (
        btrim(idempotency_key) <> ''
        AND octet_length(request_fingerprint) = 32
        AND policy_basis IN ('USER_REQUEST', 'LEGAL_ERASURE', 'RETENTION_POLICY')
        AND btrim(policy_version) <> ''
        AND btrim(trace_id) <> ''
    ),
    CONSTRAINT deletion_request_state_ck CHECK (
        state IN ('PENDING', 'CLAIMED', 'RETRY_WAIT', 'COMPLETED', 'DEAD')
        AND source_state IN ('PENDING', 'COMPLETED', 'NOT_APPLICABLE')
        AND authority_state IN ('PENDING', 'COMPLETED', 'NOT_APPLICABLE')
        AND projection_state IN ('PENDING', 'COMPLETED', 'NOT_APPLICABLE')
        AND job_state IN ('PENDING', 'COMPLETED', 'NOT_APPLICABLE')
    ),
    CONSTRAINT deletion_request_attempt_ck CHECK (
        attempt >= 0 AND max_attempts > 0 AND attempt <= max_attempts
    ),
    CONSTRAINT deletion_request_lease_ck CHECK (
        (
            state = 'CLAIMED'
            AND lease_owner IS NOT NULL
            AND btrim(lease_owner) <> ''
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND next_attempt_at IS NULL
            AND completed_at IS NULL
        )
        OR (
            state <> 'CLAIMED'
            AND lease_owner IS NULL
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    CONSTRAINT deletion_request_completion_ck CHECK (
        (
            state = 'COMPLETED'
            AND source_state IN ('COMPLETED', 'NOT_APPLICABLE')
            AND authority_state = 'COMPLETED'
            AND projection_state = 'COMPLETED'
            AND job_state = 'COMPLETED'
            AND error_class IS NULL
            AND completed_at IS NOT NULL
        )
        OR state <> 'COMPLETED'
    ),
    CONSTRAINT deletion_request_timestamps_ck CHECK (
        updated_at >= requested_at
        AND (completed_at IS NULL OR completed_at >= requested_at)
    )
);

COMMENT ON TABLE memos.deletion_request IS
    'Content-free, tenant-bound governed erasure operation with lease, retry, and per-store state.';

CREATE UNIQUE INDEX deletion_request_memory_target_uk
    ON memos.deletion_request (tenant_id, target_memory_id)
    WHERE target_type = 'MEMORY';

CREATE UNIQUE INDEX deletion_request_user_target_uk
    ON memos.deletion_request (tenant_id, target_user_id)
    WHERE target_type = 'USER';

CREATE INDEX deletion_request_claim_idx
    ON memos.deletion_request (next_attempt_at, requested_at, operation_id)
    WHERE state IN ('PENDING', 'RETRY_WAIT');

CREATE INDEX deletion_request_lease_idx
    ON memos.deletion_request (lease_expires_at, operation_id)
    WHERE state = 'CLAIMED';

CREATE TABLE memos.erasure_tombstone (
    tenant_id VARCHAR(128) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id UUID NOT NULL,
    deletion_operation_id UUID NOT NULL,
    erased_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT erasure_tombstone_pk PRIMARY KEY (tenant_id, object_type, object_id),
    CONSTRAINT erasure_tombstone_request_fk
        FOREIGN KEY (tenant_id, deletion_operation_id)
        REFERENCES memos.deletion_request (tenant_id, operation_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT erasure_tombstone_type_ck CHECK (
        object_type IN ('SOURCE_EVENT', 'MEMORY_LINEAGE')
    ),
    CONSTRAINT erasure_tombstone_tenant_ck CHECK (btrim(tenant_id) <> '')
);

COMMENT ON TABLE memos.erasure_tombstone IS
    'Append-only resurrection guard containing opaque object IDs and no content fingerprint.';

CREATE TRIGGER erasure_tombstone_append_only
BEFORE UPDATE OR DELETE ON memos.erasure_tombstone
FOR EACH ROW EXECUTE FUNCTION memos.reject_retained_memory_mutation();

CREATE FUNCTION memos.active_erasure_request(requested_tenant_id VARCHAR)
RETURNS boolean
LANGUAGE sql
VOLATILE
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM memos.deletion_request request
         WHERE request.operation_id =
                   NULLIF(current_setting('memos.erasure_request_id', true), '')::uuid
           AND request.tenant_id = requested_tenant_id
           AND request.state = 'CLAIMED'
           AND request.lease_expires_at > clock_timestamp()
    )
$$;

CREATE FUNCTION memos.guard_source_event_erasure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.source_event_id, NEW.tenant_id, NEW.user_id, NEW.agent_id,
        NEW.source_id, NEW.session_id, NEW.idempotency_key, NEW.actor_type,
        NEW.source_type, NEW.trust_level, NEW.occurred_at, NEW.received_at,
        NEW.trace_id, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.source_event_id, OLD.tenant_id, OLD.user_id, OLD.agent_id,
        OLD.source_id, OLD.session_id, OLD.idempotency_key, OLD.actor_type,
        OLD.source_type, OLD.trust_level, OLD.occurred_at, OLD.received_at,
        OLD.trace_id, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'source event identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.deletion_state = 'ACTIVE'
       AND NEW.deletion_state = 'DELETE_REQUESTED'
       AND ROW(NEW.payload, NEW.content_fingerprint, NEW.request_fingerprint)
           IS NOT DISTINCT FROM
           ROW(OLD.payload, OLD.content_fingerprint, OLD.request_fingerprint) THEN
        RETURN NEW;
    END IF;
    IF OLD.deletion_state = 'DELETE_REQUESTED'
       AND NEW.deletion_state = 'ERASED'
       AND NEW.payload = '{}'::jsonb
       AND NEW.content_fingerprint IS NULL
       AND NEW.request_fingerprint IS NULL
       AND memos.active_erasure_request(OLD.tenant_id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'source event may only advance through governed erasure'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER source_event_erasure_guard
BEFORE UPDATE ON memos.source_event
FOR EACH ROW EXECUTE FUNCTION memos.guard_source_event_erasure();

CREATE OR REPLACE FUNCTION memos.guard_memory_lineage_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.memory_id, NEW.tenant_id, NEW.user_id, NEW.agent_id,
        NEW.memory_type, NEW.subject_kind, NEW.predicate_cardinality, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.memory_id, OLD.tenant_id, OLD.user_id, OLD.agent_id,
        OLD.memory_type, OLD.subject_kind, OLD.predicate_cardinality, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'memory lineage identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.lock_version < OLD.lock_version OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'memory lineage coordination state must be monotonic'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.lifecycle_state = OLD.lifecycle_state
       AND ROW(NEW.subject_label, NEW.predicate)
           IS NOT DISTINCT FROM ROW(OLD.subject_label, OLD.predicate) THEN
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state = 'ACTIVE'
       AND NEW.lifecycle_state = 'DELETE_REQUESTED'
       AND NEW.lock_version > OLD.lock_version
       AND ROW(NEW.subject_label, NEW.predicate)
           IS NOT DISTINCT FROM ROW(OLD.subject_label, OLD.predicate) THEN
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state = 'DELETE_REQUESTED'
       AND NEW.lifecycle_state = 'ERASED'
       AND NEW.lock_version > OLD.lock_version
       AND NEW.subject_label IS NULL
       AND NEW.predicate IS NULL
       AND memos.active_erasure_request(OLD.tenant_id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'memory lineage cannot be mutated or resurrected'
        USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION memos.guard_memory_version_erasure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.version_id, NEW.tenant_id, NEW.memory_id, NEW.version_number,
        NEW.candidate_id, NEW.extractor_version, NEW.prompt_version,
        NEW.policy_version, NEW.model_version, NEW.schema_version, NEW.transaction_time
    ) IS DISTINCT FROM ROW(
        OLD.version_id, OLD.tenant_id, OLD.memory_id, OLD.version_number,
        OLD.candidate_id, OLD.extractor_version, OLD.prompt_version,
        OLD.policy_version, OLD.model_version, OLD.schema_version, OLD.transaction_time
    ) OR OLD.content_state <> 'AVAILABLE'
      OR NEW.content_state <> 'ERASED'
      OR NOT memos.active_erasure_request(OLD.tenant_id) THEN
        RAISE EXCEPTION 'memory version is append-only while retained'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER memory_version_append_only ON memos.memory_version;
CREATE TRIGGER memory_version_append_only
BEFORE UPDATE OR DELETE ON memos.memory_version
FOR EACH ROW EXECUTE FUNCTION memos.guard_memory_version_erasure();

CREATE FUNCTION memos.guard_memory_candidate_erasure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.candidate_id, NEW.tenant_id, NEW.run_id, NEW.source_event_id,
        NEW.ordinal, NEW.schema_version, NEW.source_type, NEW.source_trust,
        NEW.sensitivity, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.candidate_id, OLD.tenant_id, OLD.run_id, OLD.source_event_id,
        OLD.ordinal, OLD.schema_version, OLD.source_type, OLD.source_trust,
        OLD.sensitivity, OLD.created_at
    ) OR OLD.content_state <> 'AVAILABLE'
      OR NEW.content_state <> 'ERASED'
      OR NOT memos.active_erasure_request(OLD.tenant_id) THEN
        RAISE EXCEPTION 'memory candidate is immutable outside governed erasure'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER memory_candidate_erasure_guard
BEFORE UPDATE OR DELETE ON memos.memory_candidate
FOR EACH ROW EXECUTE FUNCTION memos.guard_memory_candidate_erasure();

CREATE FUNCTION memos.guard_memory_transition_erasure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'UPDATE'
       OR ROW(
           NEW.transition_id, NEW.tenant_id, NEW.memory_id, NEW.transition_sequence,
           NEW.operation, NEW.caused_by_candidate_id, NEW.related_version_ids,
           NEW.actor_type, NEW.transition_source, NEW.policy_version, NEW.transaction_time
       ) IS DISTINCT FROM ROW(
           OLD.transition_id, OLD.tenant_id, OLD.memory_id, OLD.transition_sequence,
           OLD.operation, OLD.caused_by_candidate_id, OLD.related_version_ids,
           OLD.actor_type, OLD.transition_source, OLD.policy_version, OLD.transaction_time
       )
       OR NEW.reason <> 'ERASED'
       OR NEW.actor_id <> 'ERASED'
       OR NOT memos.active_erasure_request(OLD.tenant_id) THEN
        RAISE EXCEPTION 'memory transition is append-only while retained'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER memory_state_transition_append_only ON memos.memory_state_transition;
CREATE TRIGGER memory_state_transition_append_only
BEFORE UPDATE OR DELETE ON memos.memory_state_transition
FOR EACH ROW EXECUTE FUNCTION memos.guard_memory_transition_erasure();

CREATE FUNCTION memos.guard_memory_mutation_erasure_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND memos.active_erasure_request(OLD.tenant_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'memory mutation request is retained unless governed erasure applies'
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER memory_mutation_request_no_delete ON memos.memory_mutation_request;
CREATE TRIGGER memory_mutation_request_no_delete
BEFORE DELETE ON memos.memory_mutation_request
FOR EACH ROW EXECUTE FUNCTION memos.guard_memory_mutation_erasure_delete();
