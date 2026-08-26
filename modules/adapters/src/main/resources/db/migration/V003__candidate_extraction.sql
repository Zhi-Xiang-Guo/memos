ALTER TABLE memos.outbox_job
    ADD CONSTRAINT outbox_job_tenant_job_source_uk
    UNIQUE (tenant_id, job_id, source_event_id);

CREATE TABLE memos.extraction_attempt (
    attempt_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    job_id UUID NOT NULL,
    job_attempt INTEGER NOT NULL,
    lease_token UUID NOT NULL,
    provider VARCHAR(128) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(128) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    provider_call_id VARCHAR(200),
    state VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    input_tokens BIGINT,
    output_tokens BIGINT,
    model_calls INTEGER NOT NULL,
    duration_ms BIGINT,
    finish_reason VARCHAR(128),
    error_class VARCHAR(128),
    CONSTRAINT extraction_attempt_pk PRIMARY KEY (attempt_id),
    CONSTRAINT extraction_attempt_tenant_attempt_uk UNIQUE (tenant_id, attempt_id),
    CONSTRAINT extraction_attempt_tenant_attempt_job_uk
        UNIQUE (tenant_id, attempt_id, job_id),
    CONSTRAINT extraction_attempt_tenant_job_attempt_lease_uk
        UNIQUE (tenant_id, job_id, job_attempt, lease_token),
    CONSTRAINT extraction_attempt_job_fk
        FOREIGN KEY (tenant_id, job_id)
        REFERENCES memos.outbox_job (tenant_id, job_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT extraction_attempt_job_attempt_ck CHECK (job_attempt > 0),
    CONSTRAINT extraction_attempt_versions_not_blank_ck CHECK (
        btrim(tenant_id) <> ''
        AND btrim(provider) <> ''
        AND btrim(model_version) <> ''
        AND btrim(prompt_version) <> ''
        AND btrim(schema_version) <> ''
        AND btrim(policy_version) <> ''
    ),
    CONSTRAINT extraction_attempt_state_ck CHECK (
        state IN (
            'STARTED',
            'SUCCEEDED',
            'INVALID_SCHEMA',
            'TRANSIENT_FAILURE',
            'PERMANENT_FAILURE',
            'ABANDONED'
        )
    ),
    CONSTRAINT extraction_attempt_usage_ck CHECK (
        (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND model_calls >= 0
        AND (duration_ms IS NULL OR duration_ms >= 0)
    ),
    CONSTRAINT extraction_attempt_time_ck CHECK (
        finished_at IS NULL OR finished_at >= started_at
    ),
    CONSTRAINT extraction_attempt_error_ck CHECK (
        (
            state = 'STARTED'
            AND finished_at IS NULL
            AND error_class IS NULL
        )
        OR
        (
            state = 'SUCCEEDED'
            AND finished_at IS NOT NULL
            AND error_class IS NULL
        )
        OR
        (
            state NOT IN ('STARTED', 'SUCCEEDED')
            AND finished_at IS NOT NULL
            AND error_class IS NOT NULL
            AND btrim(error_class) <> ''
        )
    )
);

COMMENT ON TABLE memos.extraction_attempt IS
    'Sanitized provider-call metadata only. Raw provider responses, prompts, credentials, and secrets are forbidden.';

CREATE TABLE memos.extraction_run (
    run_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    source_event_id UUID NOT NULL,
    extraction_job_id UUID NOT NULL,
    semantic_run_key VARCHAR(500) NOT NULL,
    attempt_id UUID NOT NULL,
    provider VARCHAR(128) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(128) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    candidate_count INTEGER NOT NULL,
    remember_count INTEGER NOT NULL,
    ignore_count INTEGER NOT NULL,
    review_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT extraction_run_pk PRIMARY KEY (run_id),
    CONSTRAINT extraction_run_tenant_run_uk UNIQUE (tenant_id, run_id),
    CONSTRAINT extraction_run_tenant_run_source_uk
        UNIQUE (tenant_id, run_id, source_event_id),
    CONSTRAINT extraction_run_semantic_key_uk UNIQUE (tenant_id, semantic_run_key),
    CONSTRAINT extraction_run_source_job_uk UNIQUE (tenant_id, extraction_job_id),
    CONSTRAINT extraction_run_source_job_fk
        FOREIGN KEY (tenant_id, extraction_job_id, source_event_id)
        REFERENCES memos.outbox_job (tenant_id, job_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT extraction_run_attempt_fk
        FOREIGN KEY (tenant_id, attempt_id, extraction_job_id)
        REFERENCES memos.extraction_attempt (tenant_id, attempt_id, job_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT extraction_run_identity_not_blank_ck CHECK (
        btrim(tenant_id) <> ''
        AND btrim(semantic_run_key) <> ''
        AND btrim(provider) <> ''
        AND btrim(model_version) <> ''
        AND btrim(prompt_version) <> ''
        AND btrim(schema_version) <> ''
        AND btrim(policy_version) <> ''
    ),
    CONSTRAINT extraction_run_counts_ck CHECK (
        candidate_count >= 0
        AND remember_count >= 0
        AND ignore_count >= 0
        AND review_count >= 0
        AND candidate_count = remember_count + ignore_count + review_count
    )
);

CREATE TABLE memos.memory_candidate (
    candidate_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    run_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    proposed_decision VARCHAR(32),
    subject_kind VARCHAR(64),
    subject_label VARCHAR(500),
    predicate VARCHAR(200),
    value_json JSONB,
    normalized_content TEXT,
    memory_type VARCHAR(32),
    event_time_json JSONB,
    valid_interval_json JSONB,
    importance NUMERIC(5, 4),
    confidence NUMERIC(5, 4),
    source_type VARCHAR(64) NOT NULL,
    source_trust VARCHAR(64) NOT NULL,
    sensitivity TEXT[] NOT NULL,
    relation_hints_json JSONB,
    content_fingerprint BYTEA,
    content_state VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_candidate_pk PRIMARY KEY (candidate_id),
    CONSTRAINT memory_candidate_tenant_candidate_uk UNIQUE (tenant_id, candidate_id),
    CONSTRAINT memory_candidate_tenant_run_candidate_ordinal_uk
        UNIQUE (tenant_id, run_id, candidate_id, ordinal),
    CONSTRAINT memory_candidate_semantic_position_uk
        UNIQUE (tenant_id, run_id, ordinal, schema_version),
    CONSTRAINT memory_candidate_run_source_fk
        FOREIGN KEY (tenant_id, run_id, source_event_id)
        REFERENCES memos.extraction_run (tenant_id, run_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_candidate_ordinal_ck CHECK (ordinal >= 0),
    CONSTRAINT memory_candidate_schema_not_blank_ck CHECK (btrim(schema_version) <> ''),
    CONSTRAINT memory_candidate_type_ck CHECK (
        memory_type IS NULL
        OR memory_type IN ('WORKING', 'SEMANTIC', 'EPISODIC', 'PROCEDURAL')
    ),
    CONSTRAINT memory_candidate_proposed_decision_ck CHECK (
        proposed_decision IS NULL OR proposed_decision IN ('REMEMBER', 'IGNORE', 'REVIEW')
    ),
    CONSTRAINT memory_candidate_scores_ck CHECK (
        (importance IS NULL OR (importance >= 0 AND importance <= 1))
        AND (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
    ),
    CONSTRAINT memory_candidate_source_not_blank_ck CHECK (
        btrim(source_type) <> '' AND btrim(source_trust) <> ''
    ),
    CONSTRAINT memory_candidate_content_state_ck CHECK (
        content_state IN ('AVAILABLE', 'ERASED')
    ),
    CONSTRAINT memory_candidate_content_ck CHECK (
        (
            content_state = 'AVAILABLE'
            AND proposed_decision IS NOT NULL
            AND memory_type IS NOT NULL
            AND importance IS NOT NULL
            AND confidence IS NOT NULL
            AND subject_kind IS NOT NULL
            AND btrim(subject_kind) <> ''
            AND (subject_label IS NULL OR btrim(subject_label) <> '')
            AND predicate IS NOT NULL
            AND btrim(predicate) <> ''
            AND value_json IS NOT NULL
            AND normalized_content IS NOT NULL
            AND btrim(normalized_content) <> ''
            AND content_fingerprint IS NOT NULL
        )
        OR
        (
            content_state = 'ERASED'
            AND proposed_decision IS NULL
            AND subject_kind IS NULL
            AND subject_label IS NULL
            AND predicate IS NULL
            AND value_json IS NULL
            AND normalized_content IS NULL
            AND memory_type IS NULL
            AND event_time_json IS NULL
            AND valid_interval_json IS NULL
            AND importance IS NULL
            AND confidence IS NULL
            AND relation_hints_json IS NULL
            AND content_fingerprint IS NULL
        )
    ),
    CONSTRAINT memory_candidate_event_time_shape_ck CHECK (
        event_time_json IS NULL OR jsonb_typeof(event_time_json) = 'object'
    ),
    CONSTRAINT memory_candidate_valid_interval_shape_ck CHECK (
        valid_interval_json IS NULL OR jsonb_typeof(valid_interval_json) = 'object'
    ),
    CONSTRAINT memory_candidate_relation_hints_shape_ck CHECK (
        relation_hints_json IS NULL OR jsonb_typeof(relation_hints_json) = 'array'
    )
);

COMMENT ON TABLE memos.memory_candidate IS
    'Schema-validated, policy-sanitized candidate data. It never stores a raw provider response.';

CREATE INDEX memory_candidate_run_idx
    ON memos.memory_candidate (tenant_id, run_id, ordinal);

CREATE TABLE memos.candidate_policy_decision (
    decision_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    run_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    decision VARCHAR(32) NOT NULL,
    sensitivity_action VARCHAR(32) NOT NULL,
    effective_scope VARCHAR(64) NOT NULL,
    reason_codes TEXT[] NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT candidate_policy_decision_pk PRIMARY KEY (decision_id),
    CONSTRAINT candidate_policy_decision_tenant_decision_uk
        UNIQUE (tenant_id, decision_id),
    CONSTRAINT candidate_policy_decision_semantic_uk
        UNIQUE (tenant_id, candidate_id, policy_version),
    CONSTRAINT candidate_policy_decision_candidate_fk
        FOREIGN KEY (tenant_id, run_id, candidate_id, ordinal)
        REFERENCES memos.memory_candidate (tenant_id, run_id, candidate_id, ordinal)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT candidate_policy_decision_ordinal_ck CHECK (ordinal >= 0),
    CONSTRAINT candidate_policy_decision_decision_ck CHECK (
        decision IN ('REMEMBER', 'IGNORE', 'REVIEW')
    ),
    CONSTRAINT candidate_policy_decision_action_ck CHECK (
        sensitivity_action IN ('NONE', 'RESTRICT', 'REVIEW', 'TOKENIZE', 'REDACT', 'REJECT')
    ),
    CONSTRAINT candidate_policy_decision_metadata_ck CHECK (
        btrim(effective_scope) <> '' AND btrim(policy_version) <> ''
    )
);

COMMENT ON TABLE memos.candidate_policy_decision IS
    'Append-only deterministic or authorized-review policy outcome; candidate content is referenced, not copied.';

CREATE FUNCTION memos.reject_candidate_policy_decision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'candidate_policy_decision is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER candidate_policy_decision_append_only
BEFORE UPDATE OR DELETE ON memos.candidate_policy_decision
FOR EACH ROW EXECUTE FUNCTION memos.reject_candidate_policy_decision_mutation();

CREATE TABLE memos.memory_quarantine (
    quarantine_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    run_id UUID,
    attempt_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    job_id UUID NOT NULL,
    candidate_id UUID,
    ordinal INTEGER,
    reason_code VARCHAR(128) NOT NULL,
    error_path VARCHAR(256),
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_quarantine_pk PRIMARY KEY (quarantine_id),
    CONSTRAINT memory_quarantine_tenant_quarantine_uk UNIQUE (tenant_id, quarantine_id),
    CONSTRAINT memory_quarantine_semantic_uk
        UNIQUE NULLS NOT DISTINCT (
            tenant_id, attempt_id, run_id, candidate_id, ordinal, reason_code, error_path
        ),
    CONSTRAINT memory_quarantine_attempt_fk
        FOREIGN KEY (tenant_id, attempt_id, job_id)
        REFERENCES memos.extraction_attempt (tenant_id, attempt_id, job_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_quarantine_job_source_fk
        FOREIGN KEY (tenant_id, job_id, source_event_id)
        REFERENCES memos.outbox_job (tenant_id, job_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_quarantine_run_fk
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES memos.extraction_run (tenant_id, run_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_quarantine_candidate_fk
        FOREIGN KEY (tenant_id, run_id, candidate_id, ordinal)
        REFERENCES memos.memory_candidate (tenant_id, run_id, candidate_id, ordinal)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_quarantine_candidate_ordinal_ck CHECK (
        (run_id IS NULL AND candidate_id IS NULL AND ordinal IS NULL)
        OR (run_id IS NOT NULL AND candidate_id IS NULL AND ordinal IS NULL)
        OR (run_id IS NOT NULL AND candidate_id IS NOT NULL AND ordinal IS NOT NULL AND ordinal >= 0)
    ),
    CONSTRAINT memory_quarantine_reason_not_blank_ck CHECK (btrim(reason_code) <> ''),
    CONSTRAINT memory_quarantine_state_ck CHECK (
        state IN ('OPEN', 'RELEASED', 'DISMISSED')
    )
);

COMMENT ON TABLE memos.memory_quarantine IS
    'Content-free quarantine metadata. Raw output, candidate text, prompts, and secrets are never copied here.';

CREATE INDEX memory_quarantine_open_idx
    ON memos.memory_quarantine (tenant_id, created_at, quarantine_id)
    WHERE state = 'OPEN';
