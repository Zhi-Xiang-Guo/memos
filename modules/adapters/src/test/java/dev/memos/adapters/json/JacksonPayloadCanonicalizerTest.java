package dev.memos.adapters.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

class JacksonPayloadCanonicalizerTest {
  private final JacksonPayloadCanonicalizer canonicalizer =
      new JacksonPayloadCanonicalizer(
          JsonMapper.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .build());

  @Test
  void canonicalizesObjectKeyOrder() {
    assertThat(canonicalizer.canonicalize("{\"z\":1,\"a\":{\"b\":2,\"a\":1}}"))
        .isEqualTo("{\"a\":{\"a\":1,\"b\":2},\"z\":1}");
  }

  @Test
  void rejectsNonObjectsAndDuplicateKeys() {
    assertThatThrownBy(() -> canonicalizer.canonicalize("[1,2]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize("{\"a\":1,\"a\":2}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
