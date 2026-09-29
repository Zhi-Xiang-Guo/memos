package dev.memos.answering.port;

import dev.memos.answering.model.AnswerCommand;
import dev.memos.answering.model.AnswerEvidence;

@FunctionalInterface
public interface EvidenceSearchPort {
  AnswerEvidence search(AnswerCommand command, String query);
}
