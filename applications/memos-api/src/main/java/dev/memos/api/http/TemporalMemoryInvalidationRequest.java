package dev.memos.api.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TemporalMemoryInvalidationRequest(
    @NotNull UUID versionId,
    @NotNull UUID sourceEventId,
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String reason) {}
