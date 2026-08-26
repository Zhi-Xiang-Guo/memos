package dev.memos.materialization;

public enum JobHandlingResult {
  WORK_DONE_NEEDS_COMPLETION,
  COMPLETED_ATOMICALLY,
  DEAD_ATOMICALLY,
  LEASE_LOST
}
