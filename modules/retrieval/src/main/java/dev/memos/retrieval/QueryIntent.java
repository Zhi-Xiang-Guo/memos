package dev.memos.retrieval;

import java.time.Instant;

public record QueryIntent(TemporalQueryIntent temporal, Instant targetTime) {
  public QueryIntent {
    if (temporal == null) {
      throw new IllegalArgumentException("temporal intent must not be null");
    }
  }
}
