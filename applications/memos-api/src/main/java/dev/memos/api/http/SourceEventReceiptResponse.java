package dev.memos.api.http;

import java.time.Instant;

public record SourceEventReceiptResponse(
    String sourceEventId,
    String sourceId,
    String materializationJobId,
    String disposition,
    Instant acceptedAt,
    String materializationState) {}
