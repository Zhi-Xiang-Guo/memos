package dev.memos.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.memos.governance.MemoryScope;
import dev.memos.governance.WriteCapability;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthenticatedActorTest {
  @Test
  void derivesOnlyExplicitMemoryWriterCapabilitiesFromVerifiedRoles() {
    var actor =
        new AuthenticatedActor(
            new MemoryScope("tenant", "user", "agent"),
            "subject",
            Set.of(
                MemosRoles.USER,
                MemosRoles.OPERATOR,
                MemosRoles.PROJECT_MEMORY_WRITER,
                MemosRoles.PROCEDURAL_MEMORY_WRITER));

    assertThat(actor.writeCapabilities())
        .containsExactlyInAnyOrder(
            WriteCapability.WRITE_PROJECT_MEMORY, WriteCapability.WRITE_PROCEDURAL_MEMORY);
  }

  @Test
  void operatorRoleDoesNotGrantMemoryWriteCapabilities() {
    var actor =
        new AuthenticatedActor(
            new MemoryScope("tenant", "user", "agent"),
            "subject",
            Set.of(MemosRoles.USER, MemosRoles.OPERATOR));

    assertThat(actor.writeCapabilities()).isEmpty();
  }
}
