-- Deployment-wide projection identity. Authority and usage history are never rewritten.
CREATE TABLE memos.projection_generation (
    generation BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_version VARCHAR(128) NOT NULL CHECK (btrim(model_version) <> ''),
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 1 AND 2000),
    policy_version VARCHAR(128) NOT NULL CHECK (btrim(policy_version) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION memos.projection_generation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'projection generation history is append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER projection_generation_append_only BEFORE UPDATE OR DELETE
    ON memos.projection_generation FOR EACH ROW
    EXECUTE FUNCTION memos.projection_generation_immutable();

ALTER TABLE memos.outbox_job ADD COLUMN projection_generation BIGINT
    REFERENCES memos.projection_generation(generation);

CREATE FUNCTION memos.guard_projection_job_generation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE active memos.projection_generation%ROWTYPE;
BEGIN
    IF NEW.job_type <> 'PROJECTION_BUILD' THEN RETURN NEW; END IF;
    SELECT * INTO active FROM memos.projection_generation ORDER BY generation DESC LIMIT 1;
    IF active.generation IS NULL THEN RETURN NEW; END IF;
    IF NEW.model_version <> active.model_version OR NEW.policy_version <> active.policy_version THEN
        RAISE EXCEPTION 'projection job identity mismatch' USING ERRCODE = '55000';
    END IF;
    NEW.projection_generation := active.generation;
    RETURN NEW;
END;
$$;
CREATE TRIGGER projection_job_generation BEFORE INSERT ON memos.outbox_job
    FOR EACH ROW EXECUTE FUNCTION memos.guard_projection_job_generation();

CREATE FUNCTION memos.guard_projection_checkpoint_generation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE active memos.projection_generation%ROWTYPE;
BEGIN
    SELECT * INTO active FROM memos.projection_generation ORDER BY generation DESC LIMIT 1;
    IF active.generation IS NULL THEN RETURN NEW; END IF;
    IF NEW.embedding_model_version <> active.model_version
       OR NEW.projection_policy_version <> active.policy_version
       OR EXISTS (
           SELECT 1 FROM memos.memory_search_projection
            WHERE tenant_id = NEW.tenant_id AND memory_id = NEW.memory_id
              AND (embedding_dimensions <> active.dimensions
                   OR embedding_model_version <> active.model_version)
       )
       OR NOT EXISTS (
           SELECT 1 FROM memos.outbox_job WHERE tenant_id = NEW.tenant_id
             AND job_id = NEW.source_job_id AND projection_generation = active.generation
       ) THEN
        RAISE EXCEPTION 'stale projection generation' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER projection_checkpoint_generation BEFORE INSERT OR UPDATE
    ON memos.memory_projection_checkpoint FOR EACH ROW
    EXECUTE FUNCTION memos.guard_projection_checkpoint_generation();

-- Explicit maintenance operation: brief exclusive locks hide old projections and enqueue
-- rebuilds atomically. Embedding calls happen later in the existing fenced worker.
CREATE FUNCTION memos.reconcile_projection(target_model TEXT, target_dimensions INTEGER,
    target_policy TEXT, allow_rebuild BOOLEAN) RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE active memos.projection_generation%ROWTYPE; next_generation BIGINT;
BEGIN
    IF target_model IS NULL OR btrim(target_model) = '' OR length(target_model) > 128
       OR target_policy IS NULL OR btrim(target_policy) = '' OR length(target_policy) > 128
       OR target_dimensions IS NULL OR target_dimensions NOT BETWEEN 1 AND 2000
       OR allow_rebuild IS NULL THEN
        RAISE EXCEPTION 'invalid projection identity' USING ERRCODE = '22023';
    END IF;
    LOCK TABLE memos.outbox_job, memos.memory_lineage, memos.memory_search_projection,
        memos.memory_projection_checkpoint, memos.projection_generation IN ACCESS EXCLUSIVE MODE;
    SELECT * INTO active FROM memos.projection_generation ORDER BY generation DESC LIMIT 1;
    IF active.model_version = target_model AND active.dimensions = target_dimensions
       AND active.policy_version = target_policy THEN RETURN active.generation; END IF;
    IF NOT allow_rebuild AND (
        active.generation IS NOT NULL
        OR EXISTS (SELECT 1 FROM memos.memory_lineage)
        OR EXISTS (SELECT 1 FROM memos.outbox_job WHERE job_type = 'PROJECTION_BUILD')
    ) THEN
        RAISE EXCEPTION 'PROJECTION_RECONCILIATION_REQUIRED' USING ERRCODE = '55000';
    END IF;
    INSERT INTO memos.projection_generation(model_version, dimensions, policy_version)
        VALUES (target_model, target_dimensions, target_policy) RETURNING generation INTO next_generation;
    DELETE FROM memos.memory_search_projection;
    DELETE FROM memos.memory_projection_checkpoint;
    UPDATE memos.outbox_job SET state = 'DEAD', error_class = 'PROJECTION_GENERATION_REPLACED',
        lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, next_attempt_at = NULL,
        completed_at = clock_timestamp(), updated_at = clock_timestamp()
        WHERE job_type = 'PROJECTION_BUILD' AND state IN ('PENDING', 'CLAIMED', 'RETRY_WAIT');
    INSERT INTO memos.outbox_job(job_id, tenant_id, source_event_id, job_type,
        aggregate_type, aggregate_id, semantic_job_key, policy_version, model_version,
        state, attempt, max_attempts, replay_count, next_attempt_at, payload_reference,
        trace_id, created_at, updated_at)
    SELECT md5(lineage.tenant_id || '/reconcile/' || next_generation || '/' || lineage.memory_id)::uuid,
        lineage.tenant_id, source.source_event_id, 'PROJECTION_BUILD',
        'MEMORY_TRANSITION', latest.transition_id,
        'reconcile/' || next_generation || '/' || lineage.memory_id,
        target_policy, target_model, 'PENDING', 0, 3, 0, clock_timestamp(), source.source_event_id,
        'projection-reconcile-' || next_generation, clock_timestamp(), clock_timestamp()
      FROM memos.memory_lineage lineage
      JOIN LATERAL (
          SELECT transition_id FROM memos.memory_state_transition
           WHERE tenant_id = lineage.tenant_id AND memory_id = lineage.memory_id
           ORDER BY transition_sequence DESC LIMIT 1
      ) latest ON true
      JOIN LATERAL (
          SELECT evidence.source_event_id FROM memos.memory_source evidence
          JOIN memos.source_event event ON event.tenant_id = evidence.tenant_id
           AND event.source_event_id = evidence.source_event_id
          WHERE evidence.tenant_id = lineage.tenant_id AND evidence.memory_id = lineage.memory_id
            AND event.deletion_state = 'ACTIVE'
          ORDER BY evidence.source_event_id LIMIT 1
      ) source ON true
      WHERE lineage.lifecycle_state = 'ACTIVE';
    RETURN next_generation;
END;
$$;
