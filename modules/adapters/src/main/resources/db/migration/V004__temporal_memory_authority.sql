CREATE UNIQUE INDEX memory_candidate_source_identity_uk
    ON memos.memory_candidate (tenant_id, candidate_id, source_event_id);

CREATE UNIQUE INDEX memory_candidate_provenance_identity_uk
    ON memos.memory_candidate (tenant_id, candidate_id, run_id, source_event_id);

CREATE TABLE memos.memory_lineage (
    memory_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    subject_kind VARCHAR(64) NOT NULL,
    subject_label VARCHAR(500),
    predicate VARCHAR(128) NOT NULL,
    predicate_cardinality VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_lineage_pk PRIMARY KEY (memory_id),
    CONSTRAINT memory_lineage_tenant_memory_uk UNIQUE (tenant_id, memory_id),
    CONSTRAINT memory_lineage_scope_identity_uk UNIQUE NULLS NOT DISTINCT (
        tenant_id, user_id, agent_id, memory_type, subject_kind, subject_label, predicate
    ),
    CONSTRAINT memory_lineage_scope_not_blank_ck CHECK (
        btrim(tenant_id) <> '' AND btrim(user_id) <> '' AND btrim(agent_id) <> ''
    ),
    CONSTRAINT memory_lineage_type_ck CHECK (
        memory_type IN ('WORKING', 'SEMANTIC', 'EPISODIC', 'PROCEDURAL')
    ),
    CONSTRAINT memory_lineage_subject_kind_ck CHECK (
        subject_kind IN ('USER', 'PROJECT', 'AGENT')
    ),
    CONSTRAINT memory_lineage_subject_ck CHECK (
        subject_label IS NULL OR btrim(subject_label) <> ''
    ),
    CONSTRAINT memory_lineage_predicate_ck CHECK (
        predicate ~ '^[a-z][a-z0-9_.-]{0,127}$'
    ),
    CONSTRAINT memory_lineage_cardinality_ck CHECK (
        predicate_cardinality IN ('SINGLE', 'SET')
    ),
    CONSTRAINT memory_lineage_lifecycle_ck CHECK (
        lifecycle_state IN ('ACTIVE', 'ERASED')
    ),
    CONSTRAINT memory_lineage_lock_version_ck CHECK (lock_version >= 0),
    CONSTRAINT memory_lineage_timestamps_ck CHECK (updated_at >= created_at)
);

CREATE INDEX memory_lineage_scope_idx
    ON memos.memory_lineage (
        tenant_id, user_id, agent_id, memory_type, subject_kind, predicate, memory_id
    );

CREATE FUNCTION memos.guard_memory_lineage_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.memory_id IS DISTINCT FROM OLD.memory_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
       OR NEW.memory_type IS DISTINCT FROM OLD.memory_type
       OR NEW.subject_kind IS DISTINCT FROM OLD.subject_kind
       OR NEW.subject_label IS DISTINCT FROM OLD.subject_label
       OR NEW.predicate IS DISTINCT FROM OLD.predicate
       OR NEW.predicate_cardinality IS DISTINCT FROM OLD.predicate_cardinality
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'memory lineage identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.lock_version < OLD.lock_version THEN
        RAISE EXCEPTION 'memory lineage lock_version must be monotonic'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state THEN
        IF OLD.lifecycle_state <> 'ACTIVE' OR NEW.lifecycle_state <> 'ERASED' THEN
            RAISE EXCEPTION 'memory lineage lifecycle cannot be resurrected'
                USING ERRCODE = '55000';
        END IF;
        IF NEW.lock_version <= OLD.lock_version THEN
            RAISE EXCEPTION 'memory lineage erasure must advance lock_version'
                USING ERRCODE = '55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER memory_lineage_identity_guard
BEFORE UPDATE ON memos.memory_lineage
FOR EACH ROW EXECUTE FUNCTION memos.guard_memory_lineage_identity();

CREATE TABLE memos.memory_version (
    version_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    memory_id UUID NOT NULL,
    version_number BIGINT NOT NULL,
    candidate_id UUID,
    content_state VARCHAR(32) NOT NULL,
    value_json JSONB,
    normalized_content TEXT,
    importance NUMERIC(5, 4),
    confidence NUMERIC(5, 4),
    event_time_original VARCHAR(256),
    event_time_start TIMESTAMPTZ,
    event_time_end TIMESTAMPTZ,
    event_time_precision VARCHAR(32),
    event_time_confidence NUMERIC(5, 4),
    valid_time_original VARCHAR(256),
    valid_time_start TIMESTAMPTZ,
    valid_time_end TIMESTAMPTZ,
    valid_time_precision VARCHAR(32),
    valid_time_confidence NUMERIC(5, 4),
    content_fingerprint BYTEA,
    extractor_version VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    transaction_time TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_version_pk PRIMARY KEY (version_id),
    CONSTRAINT memory_version_tenant_version_uk UNIQUE (tenant_id, version_id),
    CONSTRAINT memory_version_tenant_lineage_version_uk
        UNIQUE (tenant_id, memory_id, version_id),
    CONSTRAINT memory_version_number_uk UNIQUE (tenant_id, memory_id, version_number),
    CONSTRAINT memory_version_lineage_fk
        FOREIGN KEY (tenant_id, memory_id)
        REFERENCES memos.memory_lineage (tenant_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_version_candidate_fk
        FOREIGN KEY (tenant_id, candidate_id)
        REFERENCES memos.memory_candidate (tenant_id, candidate_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_version_number_ck CHECK (version_number > 0),
    CONSTRAINT memory_version_content_state_ck CHECK (
        content_state IN ('AVAILABLE', 'ERASED')
    ),
    CONSTRAINT memory_version_content_ck CHECK (
        (
            content_state = 'AVAILABLE'
            AND value_json IS NOT NULL
            AND normalized_content IS NOT NULL
            AND btrim(normalized_content) <> ''
            AND importance IS NOT NULL
            AND confidence IS NOT NULL
            AND content_fingerprint IS NOT NULL
        )
        OR
        (
            content_state = 'ERASED'
            AND value_json IS NULL
            AND normalized_content IS NULL
            AND importance IS NULL
            AND confidence IS NULL
            AND event_time_original IS NULL
            AND event_time_start IS NULL
            AND event_time_end IS NULL
            AND event_time_precision IS NULL
            AND event_time_confidence IS NULL
            AND valid_time_original IS NULL
            AND valid_time_start IS NULL
            AND valid_time_end IS NULL
            AND valid_time_precision IS NULL
            AND valid_time_confidence IS NULL
            AND content_fingerprint IS NULL
        )
    ),
    CONSTRAINT memory_version_scores_ck CHECK (
        (importance IS NULL OR (importance >= 0 AND importance <= 1))
        AND (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
        AND (event_time_confidence IS NULL
             OR (event_time_confidence >= 0 AND event_time_confidence <= 1))
        AND (valid_time_confidence IS NULL
             OR (valid_time_confidence >= 0 AND valid_time_confidence <= 1))
    ),
    CONSTRAINT memory_version_event_interval_ck CHECK (
        event_time_start IS NULL OR event_time_end IS NULL OR event_time_start < event_time_end
    ),
    CONSTRAINT memory_version_valid_interval_ck CHECK (
        valid_time_start IS NULL OR valid_time_end IS NULL OR valid_time_start < valid_time_end
    ),
    CONSTRAINT memory_version_event_precision_ck CHECK (
        event_time_precision IS NULL
        OR event_time_precision IN ('EXACT', 'DAY', 'MONTH', 'YEAR', 'UNKNOWN')
    ),
    CONSTRAINT memory_version_valid_precision_ck CHECK (
        valid_time_precision IS NULL
        OR valid_time_precision IN ('EXACT', 'DAY', 'MONTH', 'YEAR', 'UNKNOWN')
    ),
    CONSTRAINT memory_version_versions_not_blank_ck CHECK (
        btrim(extractor_version) <> ''
        AND btrim(prompt_version) <> ''
        AND btrim(policy_version) <> ''
        AND btrim(model_version) <> ''
        AND btrim(schema_version) <> ''
    )
);

COMMENT ON TABLE memos.memory_version IS
    'Append-only sanitized assertion versions. Correction appends; retained content is never overwritten.';

CREATE UNIQUE INDEX memory_version_candidate_uk
    ON memos.memory_version (tenant_id, candidate_id)
    WHERE candidate_id IS NOT NULL;

CREATE FUNCTION memos.enforce_memory_version_sequence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    previous_version BIGINT;
BEGIN
    PERFORM 1
      FROM memos.memory_lineage
     WHERE tenant_id = NEW.tenant_id AND memory_id = NEW.memory_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'memory lineage not found'
            USING ERRCODE = '23503';
    END IF;
    SELECT max(version_number)
      INTO previous_version
      FROM memos.memory_version
     WHERE tenant_id = NEW.tenant_id AND memory_id = NEW.memory_id;
    IF NEW.version_number <> COALESCE(previous_version, 0) + 1 THEN
        RAISE EXCEPTION 'memory version_number must be the next monotonic value'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER memory_version_sequence_guard
BEFORE INSERT ON memos.memory_version
FOR EACH ROW EXECUTE FUNCTION memos.enforce_memory_version_sequence();

CREATE FUNCTION memos.reject_retained_memory_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only while retained', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER memory_version_append_only
BEFORE UPDATE OR DELETE ON memos.memory_version
FOR EACH ROW EXECUTE FUNCTION memos.reject_retained_memory_mutation();

CREATE TABLE memos.memory_state_transition (
    transition_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    memory_id UUID NOT NULL,
    transition_sequence BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    caused_by_candidate_id UUID,
    related_version_ids UUID[] NOT NULL,
    reason VARCHAR(256) NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(200) NOT NULL,
    transition_source VARCHAR(64) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    transaction_time TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_state_transition_pk PRIMARY KEY (transition_id),
    CONSTRAINT memory_state_transition_tenant_transition_uk
        UNIQUE (tenant_id, transition_id),
    CONSTRAINT memory_state_transition_tenant_transition_lineage_uk
        UNIQUE (tenant_id, transition_id, memory_id),
    CONSTRAINT memory_state_transition_sequence_uk
        UNIQUE (tenant_id, memory_id, transition_sequence),
    CONSTRAINT memory_state_transition_lineage_fk
        FOREIGN KEY (tenant_id, memory_id)
        REFERENCES memos.memory_lineage (tenant_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_state_transition_candidate_fk
        FOREIGN KEY (tenant_id, caused_by_candidate_id)
        REFERENCES memos.memory_candidate (tenant_id, candidate_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_state_transition_sequence_ck CHECK (transition_sequence > 0),
    CONSTRAINT memory_state_transition_operation_ck CHECK (
        operation IN ('CREATE', 'REINFORCE', 'SUPERSEDE', 'COEXIST', 'CONFLICT', 'INVALIDATE')
    ),
    CONSTRAINT memory_state_transition_actor_ck CHECK (
        actor_type IN ('WORKER', 'USER', 'OPERATOR', 'SYSTEM')
    ),
    CONSTRAINT memory_state_transition_source_ck CHECK (
        transition_source IN ('CANDIDATE_MATERIALIZATION', 'CORRECTION', 'INVALIDATION')
    ),
    CONSTRAINT memory_state_transition_related_versions_ck CHECK (
        cardinality(related_version_ids) > 0
    ),
    CONSTRAINT memory_state_transition_metadata_ck CHECK (
        btrim(reason) <> ''
        AND btrim(actor_type) <> ''
        AND btrim(actor_id) <> ''
        AND btrim(transition_source) <> ''
        AND btrim(policy_version) <> ''
    )
);

CREATE INDEX memory_state_transition_replay_idx
    ON memos.memory_state_transition (tenant_id, memory_id, transition_sequence);

CREATE UNIQUE INDEX memory_state_transition_candidate_uk
    ON memos.memory_state_transition (tenant_id, caused_by_candidate_id)
    WHERE caused_by_candidate_id IS NOT NULL;

CREATE FUNCTION memos.validate_memory_state_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    related_version_id UUID;
    previous_sequence BIGINT;
BEGIN
    PERFORM 1
      FROM memos.memory_lineage
     WHERE tenant_id = NEW.tenant_id AND memory_id = NEW.memory_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'memory lineage not found'
            USING ERRCODE = '23503';
    END IF;
    SELECT max(transition_sequence)
      INTO previous_sequence
      FROM memos.memory_state_transition
     WHERE tenant_id = NEW.tenant_id AND memory_id = NEW.memory_id;
    IF NEW.transition_sequence <> COALESCE(previous_sequence, 0) + 1 THEN
        RAISE EXCEPTION 'transition_sequence must be the next monotonic value'
            USING ERRCODE = '23514';
    END IF;
    IF cardinality(NEW.related_version_ids)
       <> cardinality(ARRAY(SELECT DISTINCT unnest(NEW.related_version_ids))) THEN
        RAISE EXCEPTION 'related_version_ids must be unique'
            USING ERRCODE = '23514';
    END IF;
    FOREACH related_version_id IN ARRAY NEW.related_version_ids LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM memos.memory_version version
             WHERE version.tenant_id = NEW.tenant_id
               AND version.memory_id = NEW.memory_id
               AND version.version_id = related_version_id
        ) THEN
            RAISE EXCEPTION 'transition references an unknown lineage version'
                USING ERRCODE = '23503';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER memory_state_transition_validation
BEFORE INSERT ON memos.memory_state_transition
FOR EACH ROW EXECUTE FUNCTION memos.validate_memory_state_transition();

CREATE TRIGGER memory_state_transition_append_only
BEFORE UPDATE OR DELETE ON memos.memory_state_transition
FOR EACH ROW EXECUTE FUNCTION memos.reject_retained_memory_mutation();

CREATE TABLE memos.memory_status_change (
    tenant_id VARCHAR(128) NOT NULL,
    transition_id UUID NOT NULL,
    change_ordinal INTEGER NOT NULL,
    memory_id UUID NOT NULL,
    version_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    CONSTRAINT memory_status_change_pk PRIMARY KEY (
        tenant_id, transition_id, change_ordinal
    ),
    CONSTRAINT memory_status_change_lineage_change_uk UNIQUE (
        tenant_id, memory_id, transition_id, change_ordinal
    ),
    CONSTRAINT memory_status_change_transition_fk
        FOREIGN KEY (tenant_id, transition_id, memory_id)
        REFERENCES memos.memory_state_transition (tenant_id, transition_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_status_change_version_fk
        FOREIGN KEY (tenant_id, memory_id, version_id)
        REFERENCES memos.memory_version (tenant_id, memory_id, version_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_status_change_ordinal_ck CHECK (change_ordinal >= 0),
    CONSTRAINT memory_status_change_from_status_ck CHECK (
        from_status IS NULL
        OR from_status IN ('CURRENT', 'HISTORICAL', 'CONFLICTED', 'INVALIDATED')
    ),
    CONSTRAINT memory_status_change_to_status_ck CHECK (
        to_status IN ('CURRENT', 'HISTORICAL', 'CONFLICTED', 'INVALIDATED')
    ),
    CONSTRAINT memory_status_change_changes_ck CHECK (
        from_status IS NULL OR from_status <> to_status
    )
);

CREATE TRIGGER memory_status_change_append_only
BEFORE UPDATE OR DELETE ON memos.memory_status_change
FOR EACH ROW EXECUTE FUNCTION memos.reject_retained_memory_mutation();

CREATE TABLE memos.memory_source (
    tenant_id VARCHAR(128) NOT NULL,
    memory_id UUID NOT NULL,
    version_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    extraction_run_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    derivation_role VARCHAR(64) NOT NULL,
    evidence_ordinal INTEGER NOT NULL,
    evidence_start INTEGER,
    evidence_end INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_source_pk PRIMARY KEY (
        tenant_id, version_id, source_event_id, derivation_role, evidence_ordinal
    ),
    CONSTRAINT memory_source_version_fk
        FOREIGN KEY (tenant_id, memory_id, version_id)
        REFERENCES memos.memory_version (tenant_id, memory_id, version_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_source_event_fk
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES memos.source_event (tenant_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_source_run_fk
        FOREIGN KEY (tenant_id, extraction_run_id, source_event_id)
        REFERENCES memos.extraction_run (tenant_id, run_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_source_candidate_fk
        FOREIGN KEY (tenant_id, candidate_id)
        REFERENCES memos.memory_candidate (tenant_id, candidate_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_source_candidate_provenance_fk
        FOREIGN KEY (tenant_id, candidate_id, extraction_run_id, source_event_id)
        REFERENCES memos.memory_candidate (tenant_id, candidate_id, run_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_source_role_ck CHECK (
        btrim(derivation_role) <> '' AND btrim(policy_version) <> ''
    ),
    CONSTRAINT memory_source_ordinal_ck CHECK (evidence_ordinal >= 0),
    CONSTRAINT memory_source_evidence_span_ck CHECK (
        (evidence_start IS NULL AND evidence_end IS NULL)
        OR (
            evidence_start IS NOT NULL
            AND evidence_end IS NOT NULL
            AND evidence_start >= 0
            AND evidence_end > evidence_start
        )
    )
);

CREATE TRIGGER memory_source_append_only
BEFORE UPDATE OR DELETE ON memos.memory_source
FOR EACH ROW EXECUTE FUNCTION memos.reject_retained_memory_mutation();

CREATE FUNCTION memos.guard_memory_source_candidate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.derivation_role <> 'REINFORCED'
       AND NOT EXISTS (
           SELECT 1 FROM memos.memory_version version
            WHERE version.tenant_id = NEW.tenant_id
              AND version.version_id = NEW.version_id
              AND version.candidate_id = NEW.candidate_id
       ) THEN
        RAISE EXCEPTION 'primary memory source must match the assertion candidate'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER memory_source_candidate_guard
BEFORE INSERT ON memos.memory_source
FOR EACH ROW EXECUTE FUNCTION memos.guard_memory_source_candidate();

CREATE UNIQUE INDEX memory_source_candidate_role_uk
    ON memos.memory_source (tenant_id, version_id, candidate_id, derivation_role);

CREATE FUNCTION memos.require_memory_version_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM memos.memory_source source
         WHERE source.tenant_id = NEW.tenant_id
           AND source.memory_id = NEW.memory_id
           AND source.version_id = NEW.version_id
    ) THEN
        RAISE EXCEPTION 'memory version requires provenance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER memory_version_provenance_required
AFTER INSERT ON memos.memory_version
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION memos.require_memory_version_source();

CREATE TABLE memos.memory_current_state (
    tenant_id VARCHAR(128) NOT NULL,
    memory_id UUID NOT NULL,
    version_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    effective_valid_from TIMESTAMPTZ,
    effective_valid_to TIMESTAMPTZ,
    transition_id UUID NOT NULL,
    transition_change_ordinal INTEGER NOT NULL,
    transition_sequence BIGINT NOT NULL,
    rebuilt_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_current_state_pk PRIMARY KEY (tenant_id, memory_id, version_id),
    CONSTRAINT memory_current_state_lineage_fk
        FOREIGN KEY (tenant_id, memory_id)
        REFERENCES memos.memory_lineage (tenant_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,
    CONSTRAINT memory_current_state_version_fk
        FOREIGN KEY (tenant_id, memory_id, version_id)
        REFERENCES memos.memory_version (tenant_id, memory_id, version_id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,
    CONSTRAINT memory_current_state_transition_fk
        FOREIGN KEY (tenant_id, memory_id, transition_id, transition_change_ordinal)
        REFERENCES memos.memory_status_change (
            tenant_id, memory_id, transition_id, change_ordinal
        )
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_current_state_status_ck CHECK (
        status IN ('CURRENT', 'HISTORICAL', 'CONFLICTED', 'INVALIDATED')
    ),
    CONSTRAINT memory_current_state_sequence_ck CHECK (
        transition_sequence > 0 AND transition_change_ordinal >= 0
    ),
    CONSTRAINT memory_current_state_effective_interval_ck CHECK (
        effective_valid_from IS NULL
        OR effective_valid_to IS NULL
        OR effective_valid_from < effective_valid_to
    )
);

COMMENT ON TABLE memos.memory_current_state IS
    'Rebuildable projection of the latest append-only state change for every assertion version.';

CREATE INDEX memory_current_state_scope_status_idx
    ON memos.memory_current_state (tenant_id, status, memory_id, version_id);

CREATE FUNCTION memos.rebuild_memory_current_state(
    requested_tenant_id VARCHAR,
    requested_memory_id UUID
)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM memos.memory_current_state
     WHERE tenant_id = requested_tenant_id AND memory_id = requested_memory_id;

    INSERT INTO memos.memory_current_state (
        tenant_id, memory_id, version_id, status, effective_valid_from,
        effective_valid_to, transition_id, transition_change_ordinal,
        transition_sequence, rebuilt_at
    )
    SELECT DISTINCT ON (change.version_id)
           transition.tenant_id,
           transition.memory_id,
           change.version_id,
           change.to_status,
           version.valid_time_start,
           version.valid_time_end,
           transition.transition_id,
           change.change_ordinal,
           transition.transition_sequence,
           clock_timestamp()
      FROM memos.memory_state_transition transition
      JOIN memos.memory_status_change change
        ON change.tenant_id = transition.tenant_id
       AND change.transition_id = transition.transition_id
       AND change.memory_id = transition.memory_id
      JOIN memos.memory_version version
        ON version.tenant_id = change.tenant_id
       AND version.memory_id = change.memory_id
       AND version.version_id = change.version_id
     WHERE transition.tenant_id = requested_tenant_id
       AND transition.memory_id = requested_memory_id
     ORDER BY change.version_id,
              transition.transition_sequence DESC,
              change.change_ordinal DESC;
END;
$$;

CREATE TABLE memos.audit_event (
    audit_event_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(200) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id UUID NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT audit_event_pk PRIMARY KEY (audit_event_id),
    CONSTRAINT audit_event_tenant_event_uk UNIQUE (tenant_id, audit_event_id),
    CONSTRAINT audit_event_scope_not_blank_ck CHECK (
        btrim(tenant_id) <> '' AND btrim(user_id) <> '' AND btrim(agent_id) <> ''
    ),
    CONSTRAINT audit_event_metadata_not_blank_ck CHECK (
        btrim(actor_type) <> ''
        AND btrim(actor_id) <> ''
        AND btrim(action) <> ''
        AND btrim(target_type) <> ''
        AND btrim(outcome) <> ''
        AND btrim(reason_code) <> ''
        AND btrim(policy_version) <> ''
        AND btrim(trace_id) <> ''
    )
);

COMMENT ON TABLE memos.audit_event IS
    'Content-safe audit facts only. Payload, assertion text, raw provider output, and secrets are forbidden.';

CREATE INDEX audit_event_scope_time_idx
    ON memos.audit_event (tenant_id, user_id, agent_id, created_at, audit_event_id);

CREATE TRIGGER audit_event_append_only
BEFORE UPDATE OR DELETE ON memos.audit_event
FOR EACH ROW EXECUTE FUNCTION memos.reject_retained_memory_mutation();

CREATE UNIQUE INDEX source_event_scope_event_uk
    ON memos.source_event (tenant_id, user_id, agent_id, source_event_id);

CREATE TABLE memos.memory_mutation_request (
    mutation_request_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    mutation_type VARCHAR(32) NOT NULL,
    memory_id UUID NOT NULL,
    target_version_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    candidate_id UUID,
    state VARCHAR(32) NOT NULL,
    result_lock_version BIGINT,
    result_version_id UUID,
    result_transition_id UUID,
    trace_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT memory_mutation_request_pk PRIMARY KEY (mutation_request_id),
    CONSTRAINT memory_mutation_request_tenant_request_uk
        UNIQUE (tenant_id, mutation_request_id),
    CONSTRAINT memory_mutation_request_idempotency_uk
        UNIQUE (tenant_id, user_id, agent_id, idempotency_key),
    CONSTRAINT memory_mutation_request_lineage_fk
        FOREIGN KEY (tenant_id, memory_id)
        REFERENCES memos.memory_lineage (tenant_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_mutation_request_target_version_fk
        FOREIGN KEY (tenant_id, memory_id, target_version_id)
        REFERENCES memos.memory_version (tenant_id, memory_id, version_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_mutation_request_source_fk
        FOREIGN KEY (tenant_id, user_id, agent_id, source_event_id)
        REFERENCES memos.source_event (tenant_id, user_id, agent_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_mutation_request_candidate_source_fk
        FOREIGN KEY (tenant_id, candidate_id, source_event_id)
        REFERENCES memos.memory_candidate (tenant_id, candidate_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_mutation_request_result_version_fk
        FOREIGN KEY (tenant_id, memory_id, result_version_id)
        REFERENCES memos.memory_version (tenant_id, memory_id, version_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_mutation_request_result_transition_fk
        FOREIGN KEY (tenant_id, result_transition_id, memory_id)
        REFERENCES memos.memory_state_transition (tenant_id, transition_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_mutation_request_scope_ck CHECK (
        btrim(tenant_id) <> '' AND btrim(user_id) <> '' AND btrim(agent_id) <> ''
        AND btrim(idempotency_key) <> '' AND btrim(trace_id) <> ''
    ),
    CONSTRAINT memory_mutation_request_type_ck CHECK (
        mutation_type IN ('CORRECT', 'INVALIDATE')
    ),
    CONSTRAINT memory_mutation_request_type_result_ck CHECK (
        (
            mutation_type = 'CORRECT'
            AND candidate_id IS NOT NULL
            AND (state = 'STARTED' OR result_version_id IS NOT NULL)
        )
        OR
        (
            mutation_type = 'INVALIDATE'
            AND candidate_id IS NULL
            AND result_version_id IS NULL
        )
    ),
    CONSTRAINT memory_mutation_request_state_ck CHECK (
        state IN ('STARTED', 'COMPLETED')
    ),
    CONSTRAINT memory_mutation_request_result_ck CHECK (
        (
            state = 'STARTED'
            AND result_lock_version IS NULL
            AND result_version_id IS NULL
            AND result_transition_id IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'COMPLETED'
            AND result_lock_version IS NOT NULL
            AND result_lock_version >= 0
            AND result_transition_id IS NOT NULL
            AND completed_at IS NOT NULL
        )
    ),
    CONSTRAINT memory_mutation_request_timestamps_ck CHECK (
        completed_at IS NULL OR completed_at >= created_at
    )
);

COMMENT ON TABLE memos.memory_mutation_request IS
    'Content-free scoped idempotency authority for correction and invalidation requests.';

CREATE FUNCTION memos.guard_memory_mutation_request()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.mutation_request_id IS DISTINCT FROM OLD.mutation_request_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
       OR NEW.mutation_type IS DISTINCT FROM OLD.mutation_type
       OR NEW.memory_id IS DISTINCT FROM OLD.memory_id
       OR NEW.target_version_id IS DISTINCT FROM OLD.target_version_id
       OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id
       OR NEW.candidate_id IS DISTINCT FROM OLD.candidate_id
       OR NEW.trace_id IS DISTINCT FROM OLD.trace_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR OLD.state <> 'STARTED'
       OR NEW.state <> 'COMPLETED' THEN
        RAISE EXCEPTION 'memory mutation request identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER memory_mutation_request_guard
BEFORE UPDATE ON memos.memory_mutation_request
FOR EACH ROW EXECUTE FUNCTION memos.guard_memory_mutation_request();

CREATE TRIGGER memory_mutation_request_no_delete
BEFORE DELETE ON memos.memory_mutation_request
FOR EACH ROW EXECUTE FUNCTION memos.reject_retained_memory_mutation();
