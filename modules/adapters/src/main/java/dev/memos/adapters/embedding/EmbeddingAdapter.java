package dev.memos.adapters.embedding;

import dev.memos.materialization.ProjectionEmbeddingPort;
import dev.memos.retrieval.EmbeddingPort;

/** One configured provider used consistently by projection writes and retrieval queries. */
public interface EmbeddingAdapter extends EmbeddingPort, ProjectionEmbeddingPort {}
