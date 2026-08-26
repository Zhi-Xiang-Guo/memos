package dev.memos.adapters.json;

import dev.memos.ingestion.PayloadCanonicalizer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class JacksonPayloadCanonicalizer implements PayloadCanonicalizer {
  private final JsonMapper mapper;

  public JacksonPayloadCanonicalizer(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String canonicalize(String payload) {
    try {
      Object value = mapper.readValue(payload, Object.class);
      if (!(value instanceof java.util.Map<?, ?>)) {
        throw new IllegalArgumentException("payload must be a JSON object");
      }
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("payload must be valid JSON", exception);
    }
  }
}
