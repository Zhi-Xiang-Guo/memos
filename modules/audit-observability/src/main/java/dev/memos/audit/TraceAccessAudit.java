package dev.memos.audit;

@FunctionalInterface
public interface TraceAccessAudit {
  void record(TraceAccessAuditEvent event);
}
