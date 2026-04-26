package com.glmapper.coding.core.runtime;

import java.time.Instant;
import java.util.Map;

public record AgentRunContext(
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
        Instant startedAt,
        Map<String, Object> metadata
) {
}

