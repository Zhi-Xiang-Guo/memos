package dev.memos.governance;

import dev.memos.domain.candidate.SensitivityCategory;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record WritePolicyConfiguration(
    double reviewConfidenceBelow,
    double proceduralConfidenceMinimum,
    double proceduralImportanceMinimum,
    Map<SensitivityCategory, SensitivityAction> sensitivityActions) {
  public WritePolicyConfiguration {
    requireUnitInterval(reviewConfidenceBelow, "reviewConfidenceBelow");
    requireUnitInterval(proceduralConfidenceMinimum, "proceduralConfidenceMinimum");
    requireUnitInterval(proceduralImportanceMinimum, "proceduralImportanceMinimum");
    Objects.requireNonNull(sensitivityActions, "sensitivityActions must not be null");
    EnumMap<SensitivityCategory, SensitivityAction> copy = new EnumMap<>(SensitivityCategory.class);
    copy.putAll(sensitivityActions);
    for (SensitivityCategory category : SensitivityCategory.values()) {
      if (!copy.containsKey(category)) {
        throw new IllegalArgumentException("missing sensitivity action for " + category);
      }
    }
    sensitivityActions = Map.copyOf(copy);
  }

  public static WritePolicyConfiguration safeDefaults() {
    EnumMap<SensitivityCategory, SensitivityAction> actions =
        new EnumMap<>(SensitivityCategory.class);
    actions.put(SensitivityCategory.NONE, SensitivityAction.NONE);
    actions.put(SensitivityCategory.LOCATION, SensitivityAction.RESTRICT);
    actions.put(SensitivityCategory.CONTACT, SensitivityAction.TOKENIZE);
    actions.put(SensitivityCategory.AUTH_SECRET, SensitivityAction.REJECT);
    actions.put(SensitivityCategory.CREDENTIAL, SensitivityAction.REJECT);
    actions.put(SensitivityCategory.HEALTH, SensitivityAction.RESTRICT);
    actions.put(SensitivityCategory.FINANCIAL, SensitivityAction.RESTRICT);
    actions.put(SensitivityCategory.IDENTITY, SensitivityAction.RESTRICT);
    actions.put(SensitivityCategory.BIOMETRIC, SensitivityAction.REJECT);
    return new WritePolicyConfiguration(0.60, 0.90, 0.70, actions);
  }

  private static void requireUnitInterval(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(name + " must be between 0 and 1");
    }
  }
}
