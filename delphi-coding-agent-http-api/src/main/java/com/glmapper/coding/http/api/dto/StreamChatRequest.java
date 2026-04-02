package com.glmapper.coding.http.api.dto;

import jakarta.validation.constraints.NotBlank;

public record StreamChatRequest(
        @NotBlank String namespace,
        @NotBlank String prompt,
        @NotBlank String provider,
        @NotBlank String modelId,
        String systemPrompt,
        String sessionId,
        Double temperature,
        Integer maxTokens
) {
}
