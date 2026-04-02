package com.glmapper.coding.core.execution;

public record ExecutionContext(
        String namespace,
        String sessionId,
        String userId
) {
}
