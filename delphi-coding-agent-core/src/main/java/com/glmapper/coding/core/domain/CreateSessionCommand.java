package com.glmapper.coding.core.domain;

public record CreateSessionCommand(
        String namespace,
        String projectKey,
        String sessionName,
        String provider,
        String modelId,
        String systemPrompt
) {
}
