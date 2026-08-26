package dev.memos.worker;

import dev.memos.materialization.OutboxWorkerService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "memos.worker", name = "enabled", havingValue = "true")
public final class OutboxPollingWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPollingWorker.class);

  private final OutboxWorkerService service;
  private final AtomicBoolean running = new AtomicBoolean();

  public OutboxPollingWorker(OutboxWorkerService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${memos.worker.poll-delay:1s}")
  void poll() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      var summary = service.runOnce();
      if (summary.claimed() > 0 || summary.expiredExhausted() > 0) {
        LOGGER.info(
            "outbox poll claimed={} succeeded={} retries={} dead={} leaseLost={} expiredExhausted={}",
            summary.claimed(),
            summary.succeeded(),
            summary.retriesScheduled(),
            summary.dead(),
            summary.leaseLost(),
            summary.expiredExhausted());
      }
    } finally {
      running.set(false);
    }
  }
}
