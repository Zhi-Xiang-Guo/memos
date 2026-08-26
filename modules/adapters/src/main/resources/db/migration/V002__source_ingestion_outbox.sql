CREATE TABLE memos.source_event (
    source_event_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    session_id VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    trust_level VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    content_fingerprint BYTEA,
    request_fingerprint BYTEA NOT NULL,
    deletion_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    trace_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT source_event_pk PRIMARY KEY (source_event_id),
    CONSTRAINT source_event_tenant_event_uk UNIQUE (tenant_id, source_event_id),
    CONSTRAINT source_event_tenant_source_uk UNIQUE (tenant_id, source_id),
    CONSTRAINT source_event_tenant_idempotency_uk UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT source_event_scope_not_blank_ck CHECK (
        btrim(tenant_id) <> ''
        AND btrim(user_id) <> ''
        AND btrim(agent_id) <> ''
    ),
    CONSTRAINT source_event_external_ids_not_blank_ck CHECK (
        btrim(source_id) <> ''
        AND btrim(session_id) <> ''
        AND btrim(idempotency_key) <> ''
    ),
    CONSTRAINT source_event_actor_type_ck CHECK (
        actor_type IN ('USER', 'ASSISTANT', 'TOOL', 'APPLICATION', 'SYSTEM', 'WEB')
    ),
    CONSTRAINT source_event_source_type_ck CHECK (
        source_type IN (
            'CONVERSATION_MESSAGE',
            'TOOL_RESULT',
            'APPLICATION_EVENT',
            'DIRECT_MEMORY_COMMAND'
        )
    ),
    CONSTRAINT source_event_trust_level_ck CHECK (
        trust_level IN (
            'DIRECT_USER',
            'TRUSTED_APPLICATION',
            'ASSISTANT_GENERATED',
            'EXTERNAL_UNTRUSTED'
        )
    ),
    CONSTRAINT source_event_payload_object_ck CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT source_event_deletion_state_ck CHECK (
        deletion_state IN ('ACTIVE', 'DELETE_REQUESTED', 'ERASED')
    ),
    CONSTRAINT source_event_trace_not_blank_ck CHECK (btrim(trace_id) <> ''),
    CONSTRAINT source_event_timestamps_ck CHECK (created_at >= received_at)
);

COMMENT ON TABLE memos.source_event IS
    'Tenant-scoped authoritative source evidence. Corrections append evidence; governed erasure is a later lifecycle operation.';

CREATE INDEX source_event_scope_idx
    ON memos.source_event (tenant_id, user_id, agent_id, session_id, received_at DESC);

CREATE TABLE memos.outbox_job (
    job_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    source_event_id UUID NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    semantic_job_key VARCHAR(300) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    replay_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    payload_reference UUID NOT NULL,
    lease_owner VARCHAR(255),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    error_class VARCHAR(128),
    completed_at TIMESTAMPTZ,
    trace_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT outbox_job_pk PRIMARY KEY (job_id),
    CONSTRAINT outbox_job_tenant_job_uk UNIQUE (tenant_id, job_id),
    CONSTRAINT outbox_job_tenant_semantic_key_uk UNIQUE (tenant_id, semantic_job_key),
    CONSTRAINT outbox_job_tenant_job_semantic_uk
        UNIQUE (tenant_id, job_id, semantic_job_key),
    CONSTRAINT outbox_job_source_fk
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES memos.source_event (tenant_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT outbox_job_payload_reference_fk
        FOREIGN KEY (tenant_id, payload_reference)
        REFERENCES memos.source_event (tenant_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT outbox_job_tenant_not_blank_ck CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT outbox_job_type_not_blank_ck CHECK (btrim(job_type) <> ''),
    CONSTRAINT outbox_job_aggregate_type_not_blank_ck CHECK (btrim(aggregate_type) <> ''),
    CONSTRAINT outbox_job_payload_reference_ck CHECK (payload_reference = source_event_id),
    CONSTRAINT outbox_job_semantic_key_not_blank_ck CHECK (btrim(semantic_job_key) <> ''),
    CONSTRAINT outbox_job_versions_not_blank_ck CHECK (
        btrim(policy_version) <> ''
        AND btrim(model_version) <> ''
    ),
    CONSTRAINT outbox_job_state_ck CHECK (
        state IN ('PENDING', 'CLAIMED', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD')
    ),
    CONSTRAINT outbox_job_attempt_ck CHECK (
        attempt >= 0
        AND max_attempts > 0
        AND attempt <= max_attempts
        AND replay_count >= 0
    ),
    CONSTRAINT outbox_job_lease_state_ck CHECK (
        (
            state = 'CLAIMED'
            AND lease_owner IS NOT NULL
            AND btrim(lease_owner) <> ''
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
        )
        OR
        (
            state <> 'CLAIMED'
            AND lease_owner IS NULL
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    CONSTRAINT outbox_job_lease_expiry_ck CHECK (
        lease_expires_at IS NULL OR lease_expires_at > updated_at
    ),
    CONSTRAINT outbox_job_schedule_state_ck CHECK (
        (
            state IN ('PENDING', 'RETRY_WAIT')
            AND next_attempt_at IS NOT NULL
        )
        OR
        (
            state NOT IN ('PENDING', 'RETRY_WAIT')
            AND next_attempt_at IS NULL
        )
    ),
    CONSTRAINT outbox_job_completion_state_ck CHECK (
        (
            state IN ('SUCCEEDED', 'DEAD')
            AND completed_at IS NOT NULL
        )
        OR
        (
            state NOT IN ('SUCCEEDED', 'DEAD')
            AND completed_at IS NULL
        )
    ),
    CONSTRAINT outbox_job_trace_not_blank_ck CHECK (btrim(trace_id) <> ''),
    CONSTRAINT outbox_job_timestamps_ck CHECK (
        updated_at >= created_at
        AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

COMMENT ON TABLE memos.outbox_job IS
    'Durable at-least-once materialization intent. source_event_id is the payload reference; no source payload is copied.';

CREATE INDEX outbox_job_claim_idx
    ON memos.outbox_job (next_attempt_at, created_at, tenant_id, job_id)
    WHERE state IN ('PENDING', 'RETRY_WAIT');

CREATE INDEX outbox_job_lease_expiry_idx
    ON memos.outbox_job (lease_expires_at, tenant_id, job_id)
    WHERE state = 'CLAIMED';

CREATE INDEX outbox_job_source_idx
    ON memos.outbox_job (tenant_id, source_event_id, created_at);

CREATE TABLE memos.materialization_result (
    tenant_id VARCHAR(128) NOT NULL,
    semantic_job_key VARCHAR(300) NOT NULL,
    job_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    handler_version VARCHAR(128) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT materialization_result_pk PRIMARY KEY (tenant_id, semantic_job_key),
    CONSTRAINT materialization_result_tenant_job_uk UNIQUE (tenant_id, job_id),
    CONSTRAINT materialization_result_job_fk
        FOREIGN KEY (tenant_id, job_id, semantic_job_key)
        REFERENCES memos.outbox_job (tenant_id, job_id, semantic_job_key)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT materialization_result_source_fk
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES memos.source_event (tenant_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT materialization_result_tenant_not_blank_ck CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT materialization_result_semantic_key_not_blank_ck CHECK (
        btrim(semantic_job_key) <> ''
    ),
    CONSTRAINT materialization_result_outcome_not_blank_ck CHECK (btrim(outcome) <> ''),
    CONSTRAINT materialization_result_handler_version_not_blank_ck CHECK (
        btrim(handler_version) <> ''
    ),
    CONSTRAINT materialization_result_timestamps_ck CHECK (created_at >= completed_at)
);

COMMENT ON TABLE memos.materialization_result IS
    'Payload-free idempotency ledger for one completed logical materialization side effect.';
