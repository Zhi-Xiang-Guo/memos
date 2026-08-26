package dev.memos.materialization;

public record DownstreamMaterializationIntent(SemanticJobKey semanticJobKey, JobType jobType) {
  public DownstreamMaterializationIntent {
    if (semanticJobKey == null || jobType == null) {
      throw new IllegalArgumentException("downstream intent fields must not be null");
    }
  }
}
