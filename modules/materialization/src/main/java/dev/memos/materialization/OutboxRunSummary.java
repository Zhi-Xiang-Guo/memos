package dev.memos.materialization;

public record OutboxRunSummary(
    int claimed,
    int succeeded,
    int retriesScheduled,
    int dead,
    int leaseLost,
    int expiredExhausted) {
  public OutboxRunSummary {
    if (claimed < 0
        || succeeded < 0
        || retriesScheduled < 0
        || dead < 0
        || leaseLost < 0
        || expiredExhausted < 0) {
      throw new IllegalArgumentException("summary counters must not be negative");
    }
  }
}
