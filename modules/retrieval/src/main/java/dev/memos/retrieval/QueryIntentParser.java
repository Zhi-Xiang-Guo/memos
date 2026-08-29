package dev.memos.retrieval;

import java.time.Instant;

@FunctionalInterface
public interface QueryIntentParser {
  QueryIntent parse(String query, Instant explicitTime);
}
