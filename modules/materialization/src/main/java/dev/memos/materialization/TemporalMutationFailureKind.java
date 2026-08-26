package dev.memos.materialization;

public enum TemporalMutationFailureKind {
  NOT_FOUND,
  STALE_PRECONDITION,
  IDEMPOTENCY_CONFLICT,
  INVALID_TRANSITION
}
