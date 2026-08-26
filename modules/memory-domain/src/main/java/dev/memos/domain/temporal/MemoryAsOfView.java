package dev.memos.domain.temporal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MemoryAsOfView(
    MemoryAsOfQuery query,
    List<AssertionVersion> visibleVersions,
    Map<AssertionVersionId, AssertionStatus> statuses) {
  public MemoryAsOfView {
    Objects.requireNonNull(query, "query must not be null");
    visibleVersions = List.copyOf(Objects.requireNonNull(visibleVersions, "visibleVersions"));
    statuses = Map.copyOf(Objects.requireNonNull(statuses, "statuses"));
  }
}
