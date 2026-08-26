package dev.memos.api.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record SourceEventRequest(
    @NotBlank @Size(max = 200) String sourceId,
    @NotBlank @Size(max = 200) String sessionId,
    @NotBlank String actorType,
    @NotBlank String sourceType,
    @NotBlank String trustLevel,
    @NotNull Instant occurredAt,
    @NotNull Map<String, Object> payload) {}
