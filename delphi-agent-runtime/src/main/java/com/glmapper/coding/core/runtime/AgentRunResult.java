package com.glmapper.coding.core.runtime;

public record AgentRunResult(
        AgentRunStatus status,
        String runId,
        String finalText,
        String errorMessage
) {
}

