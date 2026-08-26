package dev.memos.materialization;

@FunctionalInterface
public interface MaterializationJobHandler {
  void handle(ClaimedJob job) throws JobHandlingException;
}
