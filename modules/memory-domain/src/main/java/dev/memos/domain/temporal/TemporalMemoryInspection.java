package dev.memos.domain.temporal;

import java.util.Optional;

public interface TemporalMemoryInspection {
  MemoryLineagePage list(MemoryListQuery query);

  Optional<MemoryLineageSnapshot> inspect(LineageScope scope, MemoryLineageId lineageId);

  MemoryAsOfView asOf(MemoryAsOfQuery query);

  MemoryDiff diff(MemoryDiffQuery query);
}
