package dev.memos.materialization;

public record SemanticJobKey(String value) {
  public SemanticJobKey {
    value = MaterializationTextValidation.requireText(value, "value", 300);
  }

  @Override
  public String toString() {
    return value;
  }
}
