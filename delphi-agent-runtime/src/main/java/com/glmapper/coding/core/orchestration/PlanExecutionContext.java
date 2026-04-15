package com.glmapper.coding.core.orchestration;

public record PlanExecutionContext(
        String namespace,
        String sessionId,
        String provider,
        String modelId,
        String systemPrompt,
        String originalPrompt,
        String goal
) {
}
