package dev.memos.api.answering;

import static org.assertj.core.api.Assertions.*;

import dev.memos.answering.model.AnswerFailure;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AnswerExecutionTest {
  @Test
  void cancellationBeforeStartReleasesCapacityExactlyOnce() throws Exception {
    try (var execution = new AnswerExecution(1)) {
      var cancelled = execution.prepare(() -> "never");
      cancelled.cancel(true);
      execution.start(cancelled);
      var next = execution.prepare(() -> "ok");
      execution.start(next);
      assertThat(next.get(1, TimeUnit.SECONDS)).isEqualTo("ok");
    }
  }

  @Test
  void activeWorkKeepsCapacityUntilItActuallyStops() throws Exception {
    try (var execution = new AnswerExecution(1)) {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      var task =
          execution.prepare(
              () -> {
                started.countDown();
                release.await();
                return "ok";
              });
      execution.start(task);
      assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> execution.prepare(() -> "excess"))
          .isInstanceOf(AnswerFailure.class)
          .hasMessage("ANSWER_BUSY");
      release.countDown();
      assertThat(task.get(1, TimeUnit.SECONDS)).isEqualTo("ok");
    }
  }
}
