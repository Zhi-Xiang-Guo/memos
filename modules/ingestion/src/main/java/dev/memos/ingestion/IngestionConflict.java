package dev.memos.ingestion;

public enum IngestionConflict {
  IDEMPOTENCY_KEY_REUSED,
  SOURCE_ID_REUSED,
  SOURCE_ERASED,
  USER_SCOPE_ERASED
}
