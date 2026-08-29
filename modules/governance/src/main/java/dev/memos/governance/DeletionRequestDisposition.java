package dev.memos.governance;

public enum DeletionRequestDisposition {
  ACCEPTED,
  IDEMPOTENT_REPLAY,
  ALREADY_REQUESTED
}
