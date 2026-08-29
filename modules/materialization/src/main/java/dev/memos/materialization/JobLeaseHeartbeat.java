package dev.memos.materialization;

import java.time.Duration;

/** Keeps claimed work fenced while it waits for or executes a slow external operation. */
public interface JobLeaseHeartbeat {
  JobLeaseHeartbeat NOOP =
      (fence, jobType, leaseDuration) ->
          new JobLeaseHeartbeatSession() {
            @Override
            public boolean leaseLost() {
              return false;
            }

            @Override
            public void close() {}
          };

  JobLeaseHeartbeatSession start(LeaseFence fence, JobType jobType, Duration leaseDuration);
}
