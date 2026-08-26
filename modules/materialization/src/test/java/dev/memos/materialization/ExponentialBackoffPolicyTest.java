package dev.memos.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExponentialBackoffPolicyTest {
  @Test
  void doublesByClaimAttemptAndCapsDeterministically() {
    ExponentialBackoffPolicy policy =
        new ExponentialBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5));

    assertEquals(Duration.ofSeconds(1), policy.delayForAttempt(1));
    assertEquals(Duration.ofSeconds(2), policy.delayForAttempt(2));
    assertEquals(Duration.ofSeconds(4), policy.delayForAttempt(3));
    assertEquals(Duration.ofSeconds(5), policy.delayForAttempt(4));
    assertEquals(Duration.ofSeconds(5), policy.delayForAttempt(100));
  }

  @Test
  void rejectsNonPositiveAttempt() {
    ExponentialBackoffPolicy policy =
        new ExponentialBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1));

    assertThrows(IllegalArgumentException.class, () -> policy.delayForAttempt(0));
  }
}
