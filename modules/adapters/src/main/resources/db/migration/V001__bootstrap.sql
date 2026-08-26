CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS memos;

COMMENT ON SCHEMA memos IS 'Authoritative MemOS records; derived projections remain rebuildable.';
