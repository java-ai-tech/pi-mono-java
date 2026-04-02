package com.glmapper.coding.http.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String namespace,
        @NotBlank String projectKey,
        @NotBlank String sessionName,
        @NotBlank String provider,
        @NotBlank String modelId,
        String systemPrompt
) {
}
