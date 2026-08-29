package dev.memos.retrieval;

import java.util.List;

@FunctionalInterface
public interface RetrievalCandidateStore {
  List<ComponentCandidate> findCandidates(CandidateStoreQuery query);
}
