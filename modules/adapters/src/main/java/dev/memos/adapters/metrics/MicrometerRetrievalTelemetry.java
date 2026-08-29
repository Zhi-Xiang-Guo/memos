package dev.memos.adapters.metrics;

import dev.memos.retrieval.RetrievalMode;
import dev.memos.retrieval.RetrievalTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

public final class MicrometerRetrievalTelemetry implements RetrievalTelemetry {
  private final MeterRegistry registry;

  public MicrometerRetrievalTelemetry(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public void record(RetrievalMode mode, String outcome, Duration duration, int selected) {
    Timer.builder("memos.retrieval.duration")
        .tag("mode", mode.name())
        .tag("outcome", outcome)
        .register(registry)
        .record(duration);
    registry
        .summary("memos.retrieval.selected", "mode", mode.name(), "outcome", outcome)
        .record(selected);
  }
}
