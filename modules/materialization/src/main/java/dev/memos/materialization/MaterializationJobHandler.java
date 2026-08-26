package dev.memos.materialization;

@FunctionalInterface
public interface MaterializationJobHandler {
  JobHandlingResult handle(ClaimedJob job) throws JobHandlingException;
}
