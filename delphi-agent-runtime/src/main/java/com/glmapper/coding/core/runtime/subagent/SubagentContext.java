package com.glmapper.coding.core.runtime.subagent;

import java.time.Instant;

public record SubagentContext(
        String subagentId,
        String parentRunId,
        String tenantId,
        String namespace,
        String userId,
        String projectKey,
        String sessionId,
        SubagentRole role,
        int depth,
        WorkspaceScope workspaceScope,
        String task,
        String context,
        int maxDurationSeconds,
        Instant startedAt
) {
}

