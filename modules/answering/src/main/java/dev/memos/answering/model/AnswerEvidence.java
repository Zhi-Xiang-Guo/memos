package dev.memos.answering.model;

import java.util.List;
import java.util.UUID;

public record AnswerEvidence(
    String rendered, List<UUID> selectedIds, List<UUID> rankedIds, String rerankOutcome) {
  public AnswerEvidence {
    selectedIds = List.copyOf(selectedIds);
    rankedIds = List.copyOf(rankedIds);
  }
}
