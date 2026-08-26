package dev.memos.materialization;

import dev.memos.domain.temporal.MemoryLineageIdentity;
import dev.memos.domain.temporal.MemoryLineageSnapshot;
import java.util.List;
import java.util.Optional;

public interface TemporalCandidateMaterializationStore {
  List<CandidateForTemporalMaterialization> loadCandidates(ClaimedJob job);

  Optional<MemoryLineageSnapshot> loadSnapshot(MemoryLineageIdentity identity);

  TemporalMaterializationCommitResult commit(CommitTemporalMaterialization command);
}
