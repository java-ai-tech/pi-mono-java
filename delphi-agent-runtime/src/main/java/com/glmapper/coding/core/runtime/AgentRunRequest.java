package com.glmapper.coding.core.runtime;

import java.util.Map;

public record AgentRunRequest(
        String runId,
        String tenantId,
        String namespace,
        String userId,
        String projectKey,
        String sessionId,
        String prompt,
        String provider,
        String modelId,
        String systemPrompt,
        RunQueueMode queueMode,
        Map<String, Object> metadata
) {
    public AgentRunRequest(
            String tenantId,
            String namespace,
            String userId,
            String projectKey,
            String sessionId,
            String prompt,
            String provider,
            String modelId,
            String systemPrompt,
            RunQueueMode queueMode,
            Map<String, Object> metadata
    ) {
        this(null, tenantId, namespace, userId, projectKey, sessionId, prompt,
                provider, modelId, systemPrompt, queueMode, metadata);
    }
}
