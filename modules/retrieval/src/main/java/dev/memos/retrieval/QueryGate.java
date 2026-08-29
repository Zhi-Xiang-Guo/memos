package dev.memos.retrieval;

@FunctionalInterface
public interface QueryGate {
  RetrievalGateDecision decide(String query);
}
