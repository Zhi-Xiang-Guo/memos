package dev.memos.api.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MemoryRetrievalRequest(
    @NotBlank @Size(max = 4096) String query,
    String mode,
    Integer limit,
    Integer componentLimit,
    @Size(max = 128) String predicate,
    @Size(max = 500) String subjectLabel,
    Instant at,
    Boolean rerank,
    Integer maxTokens) {}
