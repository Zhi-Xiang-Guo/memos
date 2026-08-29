package dev.memos.materialization;

/** One monitored lease; idempotent close waits for any in-flight renewal to finish. */
public interface JobLeaseHeartbeatSession extends AutoCloseable {
  boolean leaseLost();

  @Override
  void close();
}
