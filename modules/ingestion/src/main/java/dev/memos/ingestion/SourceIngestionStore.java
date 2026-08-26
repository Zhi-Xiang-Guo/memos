package dev.memos.ingestion;

import java.time.Instant;
import java.util.Objects;

public interface SourceIngestionStore {
  AcceptanceResult accept(SourceEvent sourceEvent, MaterializationIntent intent);

  sealed interface AcceptanceResult permits Accepted, Conflict {}

  record Accepted(
      SourceEventId sourceEventId,
      String sourceId,
      MaterializationJobId materializationJobId,
      IngestionDisposition disposition,
      Instant acceptedAt)
      implements AcceptanceResult {
    public Accepted {
      Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
      sourceId = TextValidation.requireText(sourceId, "sourceId", 200);
      Objects.requireNonNull(materializationJobId, "materializationJobId must not be null");
      Objects.requireNonNull(disposition, "disposition must not be null");
      Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
    }
  }

  record Conflict(IngestionConflict reason) implements AcceptanceResult {
    public Conflict {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
