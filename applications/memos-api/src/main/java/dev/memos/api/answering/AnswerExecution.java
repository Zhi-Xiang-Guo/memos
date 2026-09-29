package dev.memos.api.answering;

import dev.memos.answering.model.AnswerFailure;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Admission capacity is released once, including cancellation before the virtual thread starts. */
final class AnswerExecution implements AutoCloseable {
  private final Semaphore capacity;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  AnswerExecution(int maximum) {
    capacity = new Semaphore(maximum);
  }

  <T> FutureTask<T> prepare(Callable<T> work) {
    if (!capacity.tryAcquire()) throw new AnswerFailure("ANSWER_BUSY", false);
    AtomicBoolean claimed = new AtomicBoolean();
    return new FutureTask<>(
        () -> {
          if (!claimed.compareAndSet(false, true)) throw new AnswerFailure("CANCELLED", false);
          try {
            return work.call();
          } finally {
            capacity.release();
          }
        }) {
      @Override
      protected void done() {
        if (claimed.compareAndSet(false, true)) capacity.release();
      }
    };
  }

  void start(FutureTask<?> task) {
    try {
      executor.execute(task);
    } catch (RejectedExecutionException failure) {
      task.cancel(false);
      throw new AnswerFailure("ANSWER_UNAVAILABLE", false);
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
