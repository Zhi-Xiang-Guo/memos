package dev.memos.adapters.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Versioned prompt and JSON Schema resources for an explicitly enabled real provider. */
public record StructuredExtractionResources(String prompt, String jsonSchema) {
  private static final String PROMPT_RESOURCE =
      "/providers/openai-compatible/candidate-extraction-v1.txt";
  private static final String SCHEMA_RESOURCE =
      "/providers/openai-compatible/memory-candidate-v1.schema.json";

  public StructuredExtractionResources {
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt must not be blank");
    }
    if (jsonSchema == null || jsonSchema.isBlank()) {
      throw new IllegalArgumentException("jsonSchema must not be blank");
    }
  }

  public static StructuredExtractionResources loadV1() {
    return new StructuredExtractionResources(read(PROMPT_RESOURCE), read(SCHEMA_RESOURCE));
  }

  private static String read(String resource) {
    try (InputStream stream = StructuredExtractionResources.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("missing structured extraction resource: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "cannot read structured extraction resource: " + resource, exception);
    }
  }
}
