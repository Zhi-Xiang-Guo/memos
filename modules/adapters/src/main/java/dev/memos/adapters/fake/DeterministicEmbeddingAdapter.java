package dev.memos.adapters.fake;

import dev.memos.adapters.embedding.EmbeddingAdapter;
import dev.memos.materialization.ProjectionEmbedding;
import dev.memos.materialization.ProjectionEmbeddingProviderException;
import dev.memos.materialization.ProjectionEmbeddingRequest;
import dev.memos.retrieval.EmbeddingRequest;
import dev.memos.retrieval.EmbeddingResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Credential-free hashing embedding for plumbing and deterministic retrieval tests. */
public final class DeterministicEmbeddingAdapter implements EmbeddingAdapter {
  public static final int DIMENSIONS = 1_024;
  public static final String MODEL_VERSION = "deterministic-hashing-1024-v1";
  public static final String PROVIDER = "deterministic-local";

  private final String modelVersion;

  public DeterministicEmbeddingAdapter() {
    this(MODEL_VERSION);
  }

  public DeterministicEmbeddingAdapter(String modelVersion) {
    this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion must not be null");
    if (modelVersion.isBlank()) {
      throw new IllegalArgumentException("modelVersion must not be blank");
    }
  }

  @Override
  public EmbeddingResult embed(EmbeddingRequest request) {
    requireModel(request.modelVersion());
    return new EmbeddingResult(
        vector(request.text()), PROVIDER, modelVersion, tokenCount(request.text()));
  }

  @Override
  public ProjectionEmbedding embed(ProjectionEmbeddingRequest request) {
    try {
      requireModel(request.modelVersion());
    } catch (IllegalArgumentException exception) {
      throw ProjectionEmbeddingProviderException.permanentFailure(
          "EMBEDDING_MODEL_VERSION_MISMATCH", exception);
    }
    return new ProjectionEmbedding(
        vector(request.content()), PROVIDER, modelVersion, tokenCount(request.content()));
  }

  private List<Float> vector(String text) {
    String normalized =
        Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    float[] values = new float[DIMENSIONS];
    List<String> features = new ArrayList<>(List.of(normalized.split("[^\\p{L}\\p{N}]+")));
    int[] codePoints = normalized.codePoints().toArray();
    for (int size : List.of(2, 3)) {
      for (int index = 0; index + size <= codePoints.length; index++) {
        features.add(new String(codePoints, index, size));
      }
    }
    for (String feature : features) {
      if (feature.isBlank()) {
        continue;
      }
      byte[] digest = sha256(feature);
      int hash = ByteBuffer.wrap(digest).getInt();
      int bucket = Math.floorMod(hash, DIMENSIONS);
      values[bucket] += (digest[4] & 1) == 0 ? 1.0f : -1.0f;
    }
    double norm = 0.0d;
    for (float value : values) {
      norm += value * value;
    }
    norm = Math.sqrt(norm);
    List<Float> output = new ArrayList<>(DIMENSIONS);
    for (float value : values) {
      output.add(norm == 0.0d ? 0.0f : (float) (value / norm));
    }
    return List.copyOf(output);
  }

  private void requireModel(String requested) {
    if (!modelVersion.equals(requested)) {
      throw new IllegalArgumentException("requested embedding model is not configured");
    }
  }

  private static long tokenCount(String text) {
    return text.codePointCount(0, text.length());
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }
}
