package dev.memos.materialization;

public enum JobState {
  PENDING,
  CLAIMED,
  RETRY_WAIT,
  SUCCEEDED,
  DEAD
}
