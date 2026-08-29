package dev.memos.governance;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeletionCommandContractTest {
  private static final MemoryScope SCOPE = new MemoryScope("tenant-a", "user-a", "agent-a");
  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void selfServiceCannotClaimAnAdministrativePolicyBasis() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DeletionRequestCommand(
                SCOPE,
                "subject-a",
                DeletionAuthority.SELF_SERVICE,
                DeletionTargetType.MEMORY,
                UUID.randomUUID(),
                null,
                "delete-key",
                DeletionPolicyBasis.LEGAL_ERASURE,
                "trace-a",
                NOW));
  }

  @Test
  void onlyPrivacyAdministratorAuthorityCanRequeue() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DeletionRequeueCommand(
                SCOPE,
                "subject-a",
                DeletionAuthority.SELF_SERVICE,
                UUID.randomUUID(),
                "trace-a",
                NOW));
  }
}
