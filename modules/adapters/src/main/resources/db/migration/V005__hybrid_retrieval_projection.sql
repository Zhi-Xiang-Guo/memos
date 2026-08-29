CREATE UNIQUE INDEX memory_lineage_scope_memory_uk
    ON memos.memory_lineage (tenant_id, user_id, agent_id, memory_id);

CREATE TABLE memos.memory_search_projection (
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    memory_id UUID NOT NULL,
    version_id UUID NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    subject_kind VARCHAR(64) NOT NULL,
    subject_label VARCHAR(500),
    predicate VARCHAR(128) NOT NULL,
    truth_status VARCHAR(32) NOT NULL,
    normalized_content TEXT NOT NULL,
    valid_time_start TIMESTAMPTZ,
    valid_time_end TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    source_event_ids UUID[] NOT NULL,
    embedding_model_version VARCHAR(128) NOT NULL,
    embedding_dimensions INTEGER NOT NULL,
    embedding vector(64) NOT NULL,
    lexical_document tsvector GENERATED ALWAYS AS (
        to_tsvector('simple'::regconfig, normalized_content)
    ) STORED,
    projection_policy_version VARCHAR(128) NOT NULL,
    transition_id UUID NOT NULL,
    transition_sequence BIGINT NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_search_projection_pk PRIMARY KEY (tenant_id, version_id),
    CONSTRAINT memory_search_projection_scope_version_uk UNIQUE (
        tenant_id, user_id, agent_id, version_id
    ),
    CONSTRAINT memory_search_projection_lineage_fk
        FOREIGN KEY (tenant_id, user_id, agent_id, memory_id)
        REFERENCES memos.memory_lineage (tenant_id, user_id, agent_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_search_projection_version_fk
        FOREIGN KEY (tenant_id, memory_id, version_id)
        REFERENCES memos.memory_version (tenant_id, memory_id, version_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_search_projection_transition_fk
        FOREIGN KEY (tenant_id, transition_id, memory_id)
        REFERENCES memos.memory_state_transition (tenant_id, transition_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_search_projection_scope_ck CHECK (
        btrim(tenant_id) <> '' AND btrim(user_id) <> '' AND btrim(agent_id) <> ''
    ),
    CONSTRAINT memory_search_projection_type_ck CHECK (
        memory_type IN ('WORKING', 'SEMANTIC', 'EPISODIC', 'PROCEDURAL')
    ),
    CONSTRAINT memory_search_projection_subject_ck CHECK (
        subject_kind IN ('USER', 'PROJECT', 'AGENT')
        AND (subject_label IS NULL OR btrim(subject_label) <> '')
    ),
    CONSTRAINT memory_search_projection_predicate_ck CHECK (
        predicate ~ '^[a-z][a-z0-9_.-]{0,127}$'
    ),
    CONSTRAINT memory_search_projection_truth_ck CHECK (
        truth_status IN ('CURRENT', 'HISTORICAL', 'CONFLICTED')
    ),
    CONSTRAINT memory_search_projection_content_ck CHECK (
        btrim(normalized_content) <> ''
    ),
    CONSTRAINT memory_search_projection_valid_time_ck CHECK (
        valid_time_start IS NULL
        OR valid_time_end IS NULL
        OR valid_time_start < valid_time_end
    ),
    CONSTRAINT memory_search_projection_sources_ck CHECK (
        cardinality(source_event_ids) > 0
    ),
    CONSTRAINT memory_search_projection_embedding_ck CHECK (
        embedding_dimensions = 64
        AND vector_dims(embedding) = embedding_dimensions
        AND btrim(embedding_model_version) <> ''
    ),
    CONSTRAINT memory_search_projection_policy_ck CHECK (
        btrim(projection_policy_version) <> '' AND transition_sequence > 0
    )
);

COMMENT ON TABLE memos.memory_search_projection IS
    'Rebuildable scoped lexical/vector projection. Authoritative truth remains memory_version plus transitions.';

CREATE INDEX memory_search_projection_scope_status_idx
    ON memos.memory_search_projection (
        tenant_id, user_id, agent_id, truth_status, memory_type, predicate, version_id
    );

CREATE INDEX memory_search_projection_temporal_idx
    ON memos.memory_search_projection (
        tenant_id, user_id, agent_id, valid_time_start, valid_time_end, recorded_at DESC
    );

CREATE INDEX memory_search_projection_lexical_idx
    ON memos.memory_search_projection USING gin (lexical_document);

CREATE INDEX memory_search_projection_embedding_hnsw_idx
    ON memos.memory_search_projection USING hnsw (embedding vector_cosine_ops);

CREATE TABLE memos.memory_projection_checkpoint (
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    memory_id UUID NOT NULL,
    transition_id UUID NOT NULL,
    transition_sequence BIGINT NOT NULL,
    projection_policy_version VARCHAR(128) NOT NULL,
    embedding_model_version VARCHAR(128) NOT NULL,
    source_job_id UUID NOT NULL,
    projected_version_count INTEGER NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_projection_checkpoint_pk PRIMARY KEY (tenant_id, memory_id),
    CONSTRAINT memory_projection_checkpoint_scope_memory_uk UNIQUE (
        tenant_id, user_id, agent_id, memory_id
    ),
    CONSTRAINT memory_projection_checkpoint_lineage_fk
        FOREIGN KEY (tenant_id, user_id, agent_id, memory_id)
        REFERENCES memos.memory_lineage (tenant_id, user_id, agent_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_projection_checkpoint_transition_fk
        FOREIGN KEY (tenant_id, transition_id, memory_id)
        REFERENCES memos.memory_state_transition (tenant_id, transition_id, memory_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_projection_checkpoint_job_fk
        FOREIGN KEY (tenant_id, source_job_id)
        REFERENCES memos.outbox_job (tenant_id, job_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT memory_projection_checkpoint_values_ck CHECK (
        transition_sequence > 0
        AND projected_version_count >= 0
        AND btrim(projection_policy_version) <> ''
        AND btrim(embedding_model_version) <> ''
    )
);

COMMENT ON TABLE memos.memory_projection_checkpoint IS
    'Latest queryable transition per lineage; used as the explicit retrieval freshness watermark.';

UPDATE memos.outbox_job
   SET model_version = 'deterministic-hashing-64-v1',
       updated_at = clock_timestamp()
 WHERE job_type = 'PROJECTION_BUILD'
   AND state IN ('PENDING', 'RETRY_WAIT');
