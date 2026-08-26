package dev.memos.ingestion;

import java.time.Instant;
import java.util.Objects;

public record IngestionReceipt(
    SourceEventId sourceEventId,
    String sourceId,
    MaterializationJobId materializationJobId,
    IngestionDisposition disposition,
    Instant acceptedAt) {
  public IngestionReceipt {
    Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    sourceId = TextValidation.requireText(sourceId, "sourceId", 200);
    Objects.requireNonNull(materializationJobId, "materializationJobId must not be null");
    Objects.requireNonNull(disposition, "disposition must not be null");
    Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
  }

  public boolean replayed() {
    return disposition != IngestionDisposition.ACCEPTED;
  }
}
