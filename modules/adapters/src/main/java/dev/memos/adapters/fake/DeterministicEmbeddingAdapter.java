package dev.memos.adapters.fake;

import dev.memos.retrieval.EmbeddingPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public final class DeterministicEmbeddingAdapter implements EmbeddingPort {
  private static final int DIMENSIONS = 16;

  @Override
  public List<Double> embed(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      List<Double> vector = new ArrayList<>(DIMENSIONS);
      for (int index = 0; index < DIMENSIONS; index++) {
        vector.add((digest[index] & 0xff) / 255.0d);
      }
      return List.copyOf(vector);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }
}
