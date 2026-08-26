package dev.memos.ingestion;

import java.time.Instant;
import java.util.Objects;

public record IngestionReceipt(String sourceEventId, Instant acceptedAt) {
  public IngestionReceipt {
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
  }
}
