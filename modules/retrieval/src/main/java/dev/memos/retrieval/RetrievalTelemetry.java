package dev.memos.retrieval;

import java.time.Duration;

public interface RetrievalTelemetry {
  RetrievalTelemetry NOOP = (mode, outcome, duration, selected) -> {};

  void record(RetrievalMode mode, String outcome, Duration duration, int selected);
}
