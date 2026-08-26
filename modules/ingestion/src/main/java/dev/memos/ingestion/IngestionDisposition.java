package dev.memos.ingestion;

public enum IngestionDisposition {
  ACCEPTED,
  IDEMPOTENT_REPLAY,
  SOURCE_REPLAY
}
