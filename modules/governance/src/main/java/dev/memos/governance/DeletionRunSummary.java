package dev.memos.governance;

public record DeletionRunSummary(
    int claimed, int completed, int retriesScheduled, int dead, int leaseLost, int expiredDead) {}
