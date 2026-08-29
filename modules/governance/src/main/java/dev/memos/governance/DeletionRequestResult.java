package dev.memos.governance;

import java.util.Objects;

public record DeletionRequestResult(
    DeletionOperation operation, DeletionRequestDisposition disposition) {
  public DeletionRequestResult {
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(disposition, "disposition must not be null");
  }
}
