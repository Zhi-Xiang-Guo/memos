package dev.memos.materialization;

public interface ExtractionCommitStore {
  ExtractionAttemptStartResult startAttempt(StartExtractionAttempt command);

  ExtractionCommitResult commitSuccess(CommitExtractionSuccess command);

  ExtractionCommitResult commitInvalidSchema(CommitInvalidExtraction command);

  ExtractionCommitResult commitSkipped(CommitSkippedExtraction command);

  void recordTransientFailure(RecordTransientExtractionFailure command);
}
