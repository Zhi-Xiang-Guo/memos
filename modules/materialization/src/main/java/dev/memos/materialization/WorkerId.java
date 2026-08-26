package dev.memos.materialization;

public record WorkerId(String value) {
  public WorkerId {
    value = MaterializationTextValidation.requireText(value, "value", 200);
  }

  @Override
  public String toString() {
    return value;
  }
}
