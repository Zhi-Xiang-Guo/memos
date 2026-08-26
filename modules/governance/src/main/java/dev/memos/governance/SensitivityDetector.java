package dev.memos.governance;

import dev.memos.domain.candidate.MemoryCandidateProposal;
import dev.memos.domain.candidate.SensitivityCategory;
import java.util.Set;

@FunctionalInterface
public interface SensitivityDetector {
  Set<SensitivityCategory> detect(MemoryCandidateProposal proposal);
}
