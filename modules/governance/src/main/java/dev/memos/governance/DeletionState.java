package dev.memos.governance;

public enum DeletionState {
  PENDING,
  CLAIMED,
  RETRY_WAIT,
  COMPLETED,
  DEAD
}
