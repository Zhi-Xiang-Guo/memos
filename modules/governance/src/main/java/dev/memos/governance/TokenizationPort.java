package dev.memos.governance;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import java.util.Optional;

@FunctionalInterface
public interface TokenizationPort {
  TokenizationPort UNCONFIGURED = proposal -> Optional.empty();

  Optional<MemoryCandidateProposal> tokenize(MemoryCandidateProposal proposal);
}
