package dev.memos.adapters.system;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.temporal.MemoryLineageId;
import dev.memos.governance.MemoryScope;
import dev.memos.materialization.TemporalLineageIdentifier;
import java.util.Objects;
import java.util.UUID;

/** Generates an opaque proposed ID; the authority resolves an existing natural-key lineage first. */
public final class RandomTemporalLineageIdentifier implements TemporalLineageIdentifier {
  @Override
  public MemoryLineageId identify(MemoryScope scope, MemoryCandidateProposal proposal) {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(proposal, "proposal must not be null");
    return new MemoryLineageId(UUID.randomUUID());
  }
}
