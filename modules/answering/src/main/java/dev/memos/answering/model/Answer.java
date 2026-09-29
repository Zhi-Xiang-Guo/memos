package dev.memos.answering.model;

import java.util.List;
import java.util.UUID;

public record Answer(String answer, boolean abstain, List<UUID> citations) {
  public Answer {
    if (answer == null || answer.isBlank() || answer.length() > 16384)
      throw new IllegalArgumentException("invalid answer text");
    citations = List.copyOf(citations);
    if (citations.size() > 50 || citations.stream().distinct().count() != citations.size())
      throw new IllegalArgumentException("invalid citations");
    if (abstain && !citations.isEmpty() || !abstain && citations.isEmpty())
      throw new IllegalArgumentException("answer/citation contract mismatch");
  }

  public static Answer insufficient() {
    return new Answer("现有证据不足，无法回答。", true, List.of());
  }
}
