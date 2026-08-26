package dev.memos.adapters.system;

import dev.memos.ingestion.IngestionIdentifierGenerator;
import dev.memos.ingestion.MaterializationJobId;
import dev.memos.ingestion.SourceEventId;
import java.util.UUID;

public final class UuidIngestionIdentifierGenerator implements IngestionIdentifierGenerator {
  @Override
  public SourceEventId newSourceEventId() {
    return new SourceEventId(UUID.randomUUID());
  }

  @Override
  public MaterializationJobId newMaterializationJobId() {
    return new MaterializationJobId(UUID.randomUUID());
  }
}
