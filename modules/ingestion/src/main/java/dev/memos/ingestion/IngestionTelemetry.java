package dev.memos.ingestion;

public interface IngestionTelemetry {
  IngestionTelemetry NOOP = new IngestionTelemetry() {};

  default void accepted(IngestionDisposition disposition) {}

  default void rejected(IngestionConflict conflict) {}
}
