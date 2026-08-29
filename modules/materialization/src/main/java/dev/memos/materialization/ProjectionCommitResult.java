package dev.memos.materialization;

public enum ProjectionCommitResult {
  COMMITTED,
  SUPERSEDED,
  ALREADY_COMMITTED,
  LEASE_LOST
}
