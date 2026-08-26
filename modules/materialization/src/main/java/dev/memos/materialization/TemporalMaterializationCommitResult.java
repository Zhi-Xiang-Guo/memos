package dev.memos.materialization;

public enum TemporalMaterializationCommitResult {
  COMMITTED,
  ALREADY_COMMITTED,
  LEASE_LOST,
  OPTIMISTIC_CONFLICT
}
