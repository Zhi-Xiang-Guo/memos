package dev.memos.adapters.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MaterializationConfigurationTest {
  @Test
  void codexProxyRecordsItsDistinctDevelopmentProvenance() {
    var properties =
        new ExtractionProperties(
            "codex-proxy",
            "http://127.0.0.1:31415/v1",
            "test-token",
            "gpt-5.6-luna",
            "local-codex-proxy-dev-unpinned",
            null,
            "candidate-extraction-v1-inline-schema",
            "memory-candidate.v1",
            "write-policy-v1",
            42,
            Duration.ofSeconds(300));

    var identity = new MaterializationConfiguration().extractionProviderIdentity(properties);

    assertThat(identity.provider()).isEqualTo("openai-compatible");
    assertThat(identity.modelVersion()).isEqualTo("local-codex-proxy-dev-unpinned");
    assertThat(identity.promptVersion()).isEqualTo("candidate-extraction-v1-inline-schema");
  }
}
