package com.glmapper.coding.sdk;

public record CreateAgentSessionOptions(
        String namespace,
        String projectKey,
        String sessionName,
        String provider,
        String modelId,
        String systemPrompt
) {
}
