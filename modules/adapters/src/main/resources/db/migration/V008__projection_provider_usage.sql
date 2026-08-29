CREATE TABLE memos.projection_provider_usage (
    tenant_id VARCHAR(128) NOT NULL,
    job_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    job_attempt INTEGER NOT NULL,
    provider VARCHAR(128),
    model_version VARCHAR(128) NOT NULL,
    input_tokens BIGINT NOT NULL,
    model_calls INTEGER NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT projection_provider_usage_pk PRIMARY KEY (tenant_id, job_id),
    CONSTRAINT projection_provider_usage_job_fk
        FOREIGN KEY (tenant_id, job_id, source_event_id)
        REFERENCES memos.outbox_job (tenant_id, job_id, source_event_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT projection_provider_usage_values_ck CHECK (
        job_attempt > 0
        AND input_tokens >= 0
        AND model_calls >= 0
        AND btrim(model_version) <> ''
        AND (
            (model_calls = 0 AND provider IS NULL AND input_tokens = 0)
            OR
            (model_calls > 0 AND provider IS NOT NULL AND btrim(provider) <> '')
        )
    )
);

CREATE INDEX projection_provider_usage_source_idx
    ON memos.projection_provider_usage (tenant_id, source_event_id);

COMMENT ON TABLE memos.projection_provider_usage IS
    'Content-free provider usage committed with a successful or superseded projection job.';
