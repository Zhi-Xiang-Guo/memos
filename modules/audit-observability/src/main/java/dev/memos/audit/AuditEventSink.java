package dev.memos.audit;

@FunctionalInterface
public interface AuditEventSink {
  void record(AuditEvent event);
}
