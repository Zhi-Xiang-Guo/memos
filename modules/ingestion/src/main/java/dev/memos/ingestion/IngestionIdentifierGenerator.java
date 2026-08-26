package dev.memos.ingestion;

public interface IngestionIdentifierGenerator {
  SourceEventId newSourceEventId();

  MaterializationJobId newMaterializationJobId();
}
