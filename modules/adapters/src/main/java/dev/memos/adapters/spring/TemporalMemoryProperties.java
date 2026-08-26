package dev.memos.adapters.spring;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memos.temporal")
public record TemporalMemoryProperties(
    String projectionPolicyVersion, Set<String> setValuedPredicates) {
  public TemporalMemoryProperties {
    setValuedPredicates = setValuedPredicates == null ? Set.of() : Set.copyOf(setValuedPredicates);
  }
}
