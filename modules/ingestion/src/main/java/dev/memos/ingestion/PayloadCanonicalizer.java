package dev.memos.ingestion;

@FunctionalInterface
public interface PayloadCanonicalizer {
  String canonicalize(String payload);
}
