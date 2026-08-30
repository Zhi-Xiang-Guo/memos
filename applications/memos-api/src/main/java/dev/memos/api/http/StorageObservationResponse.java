package dev.memos.api.http;

import dev.memos.materialization.StorageObservation;
import java.util.List;

public record StorageObservationResponse(
    String schemaVersion, ScopeResponse scope, DatabaseResponse database) {
  static StorageObservationResponse from(StorageObservation observation) {
    List<RelationResponse> relations =
        observation.relations().stream()
            .map(
                value -> new RelationResponse(value.relation(), value.rowCount(), value.rowBytes()))
            .toList();
    return new StorageObservationResponse(
        "memos-storage-observation.v1",
        new ScopeResponse(observation.scopeRowCount(), observation.scopeRowBytes(), relations),
        new DatabaseResponse(
            observation.databaseTableBytes(),
            observation.databaseIndexBytes(),
            observation.databaseTotalBytes()));
  }

  public record ScopeResponse(long rowCount, long rowBytes, List<RelationResponse> relations) {}

  public record RelationResponse(String relation, long rowCount, long rowBytes) {}

  public record DatabaseResponse(long tableBytes, long indexBytes, long totalBytes) {}
}
