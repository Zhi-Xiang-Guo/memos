ALTER TABLE memos.source_event
    ADD COLUMN write_capabilities TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

ALTER TABLE memos.source_event
    ADD CONSTRAINT source_event_write_capabilities_ck CHECK (
        write_capabilities <@ ARRAY[
            'WRITE_PROJECT_MEMORY',
            'WRITE_PROCEDURAL_MEMORY'
        ]::TEXT[]
    );

COMMENT ON COLUMN memos.source_event.write_capabilities IS
    'Immutable write grants derived from the verified actor token when this source was accepted.';
