package dev.memos.materialization;

public record StorageRelationObservation(String relation, long rowCount, long rowBytes) {
  public StorageRelationObservation {
    if (relation == null || !relation.matches("memos\\.[a-z][a-z0-9_]{0,126}")) {
      throw new IllegalArgumentException("relation must be a bounded memos relation name");
    }
    if (rowCount < 0 || rowBytes < 0) {
      throw new IllegalArgumentException("storage relation counts and bytes must be non-negative");
    }
    if (rowCount == 0 && rowBytes != 0) {
      throw new IllegalArgumentException("an empty storage relation cannot report row bytes");
    }
  }
}
