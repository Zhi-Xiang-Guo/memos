package dev.memos.adapters.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StructuredExtractionResourcesTest {
  @Test
  void policyV3DefinesEnumsAndNoiseRulesForSmallLocalModels() {
    String prompt = StructuredExtractionResources.loadPolicyV3().prompt();

    assertThat(prompt)
        .contains(
            "SEMANTIC =",
            "EPISODIC =",
            "WORKING =",
            "PROCEDURAL =",
            "AUTH_SECRET =",
            "CREDENTIAL =",
            "Return candidates=[]",
            "same predicate for later updates",
            "proposed_decision=REMEMBER");
  }
}
