package dev.memos.worker;

import dev.memos.governance.DeletionWorkerService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
public final class DeletionPollingWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeletionPollingWorker.class);

  private final DeletionWorkerService service;
  private final AtomicBoolean running = new AtomicBoolean();

  public DeletionPollingWorker(DeletionWorkerService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${memos.deletion.poll-delay:1s}")
  void poll() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      var summary = service.runOnce();
      if (summary.claimed() > 0 || summary.expiredDead() > 0) {
        LOGGER.info(
            "deletion poll claimed={} completed={} retries={} dead={} leaseLost={} expiredDead={}",
            summary.claimed(),
            summary.completed(),
            summary.retriesScheduled(),
            summary.dead(),
            summary.leaseLost(),
            summary.expiredDead());
      }
    } finally {
      running.set(false);
    }
  }
}
