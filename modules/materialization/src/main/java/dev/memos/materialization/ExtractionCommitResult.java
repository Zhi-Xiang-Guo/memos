package dev.memos.materialization;

public enum ExtractionCommitResult {
  COMMITTED,
  ALREADY_COMMITTED,
  LEASE_LOST
}
