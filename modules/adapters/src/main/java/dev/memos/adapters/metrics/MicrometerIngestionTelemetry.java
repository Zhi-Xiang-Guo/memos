package dev.memos.adapters.metrics;

import dev.memos.ingestion.IngestionConflict;
import dev.memos.ingestion.IngestionDisposition;
import dev.memos.ingestion.IngestionTelemetry;
import io.micrometer.core.instrument.MeterRegistry;

public final class MicrometerIngestionTelemetry implements IngestionTelemetry {
  private final MeterRegistry registry;

  public MicrometerIngestionTelemetry(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void accepted(IngestionDisposition disposition) {
    registry.counter("memos.ingestion.requests", "outcome", disposition.name()).increment();
  }

  @Override
  public void rejected(IngestionConflict conflict) {
    registry.counter("memos.ingestion.requests", "outcome", conflict.name()).increment();
  }
}
