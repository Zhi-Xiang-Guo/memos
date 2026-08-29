package dev.memos.api.http;

import dev.memos.materialization.SourceMaterialization;
import java.time.Instant;
import java.util.List;

public record SourceMaterializationResponse(
    String sourceEventId,
    String status,
    Instant createdAt,
    Instant updatedAt,
    Instant settledAt,
    List<MaterializationJobResponse> jobs) {
  static SourceMaterializationResponse from(SourceMaterialization materialization) {
    return new SourceMaterializationResponse(
        materialization.sourceEventId().toString(),
        materialization.state().name(),
        materialization.createdAt(),
        materialization.updatedAt(),
        materialization.settledAt(),
        materialization.jobs().stream().map(MaterializationJobResponse::from).toList());
  }
}
