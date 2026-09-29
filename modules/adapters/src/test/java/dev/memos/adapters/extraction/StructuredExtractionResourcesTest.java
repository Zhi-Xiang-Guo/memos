package dev.memos.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StructuredExtractionResourcesTest {
  @Test
  void preservesFrozenV1AndSchemaWhileSelectingExplicitV2() {
    StructuredExtractionResources v1 = StructuredExtractionResources.loadV1();
    StructuredExtractionResources v2 =
        StructuredExtractionResources.load("candidate-extraction-v2");

    assertThat(StructuredExtractionResources.load("candidate-extraction-v1")).isEqualTo(v1);
    assertThat(v2.jsonSchema()).isEqualTo(v1.jsonSchema());
    assertThat(v2.prompt()).isNotEqualTo(v1.prompt()).contains("SEMANTIC", "PROCEDURAL");
  }

  @Test
  void rejectsUnknownPromptRatherThanMislabelingV1() {
    assertThatThrownBy(() -> StructuredExtractionResources.load("unknown-prompt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported structured extraction prompt version");
  }
}
