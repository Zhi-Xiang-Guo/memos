package dev.memos.materialization;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record StorageObservation(
    List<StorageRelationObservation> relations,
    long scopeRowCount,
    long scopeRowBytes,
    long databaseTableBytes,
    long databaseIndexBytes,
    long databaseTotalBytes) {
  public StorageObservation {
    relations = List.copyOf(Objects.requireNonNull(relations, "relations must not be null"));
    if (relations.isEmpty()) {
      throw new IllegalArgumentException("relations must not be empty");
    }
    var names = new HashSet<String>();
    String previous = null;
    long observedRows = 0;
    long observedBytes = 0;
    for (StorageRelationObservation relation : relations) {
      if (!names.add(relation.relation())) {
        throw new IllegalArgumentException("storage relations must be unique");
      }
      if (previous != null && previous.compareTo(relation.relation()) >= 0) {
        throw new IllegalArgumentException("storage relations must be sorted");
      }
      previous = relation.relation();
      observedRows = Math.addExact(observedRows, relation.rowCount());
      observedBytes = Math.addExact(observedBytes, relation.rowBytes());
    }
    if (scopeRowCount < 0
        || scopeRowBytes < 0
        || databaseTableBytes < 0
        || databaseIndexBytes < 0
        || databaseTotalBytes < 0) {
      throw new IllegalArgumentException("storage totals must be non-negative");
    }
    if (scopeRowCount != observedRows || scopeRowBytes != observedBytes) {
      throw new IllegalArgumentException("scope totals must equal relation observations");
    }
    if (databaseTotalBytes != Math.addExact(databaseTableBytes, databaseIndexBytes)) {
      throw new IllegalArgumentException("database total bytes must equal table plus index bytes");
    }
  }
}
