package dev.memos.api.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeletionRequestBody(
    @NotBlank @Pattern(regexp = "USER_REQUEST|LEGAL_ERASURE|RETENTION_POLICY")
        String policyBasis) {}
