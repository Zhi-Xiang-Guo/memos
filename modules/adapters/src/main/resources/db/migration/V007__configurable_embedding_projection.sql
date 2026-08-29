DROP INDEX memos.memory_search_projection_embedding_hnsw_idx;

ALTER TABLE memos.memory_search_projection
    DROP CONSTRAINT memory_search_projection_embedding_ck;

ALTER TABLE memos.memory_search_projection
    ALTER COLUMN embedding TYPE vector USING embedding::vector;

ALTER TABLE memos.memory_search_projection
    ADD CONSTRAINT memory_search_projection_embedding_ck CHECK (
        embedding_dimensions BETWEEN 1 AND 2000
        AND vector_dims(embedding) = embedding_dimensions
        AND btrim(embedding_model_version) <> ''
    );

CREATE INDEX memory_search_projection_embedding_hnsw_idx
    ON memos.memory_search_projection
    USING hnsw ((embedding::vector(1024)) vector_cosine_ops)
    WHERE embedding_dimensions = 1024;

UPDATE memos.outbox_job
   SET model_version = 'deterministic-hashing-1024-v1',
       updated_at = clock_timestamp()
 WHERE job_type = 'PROJECTION_BUILD'
   AND model_version = 'deterministic-hashing-64-v1'
   AND state IN ('PENDING', 'RETRY_WAIT');

COMMENT ON COLUMN memos.memory_search_projection.embedding IS
    'Rebuildable provider vector. The v1 optimized HNSW projection is dimension 1024; other configured dimensions require an explicit index migration.';
