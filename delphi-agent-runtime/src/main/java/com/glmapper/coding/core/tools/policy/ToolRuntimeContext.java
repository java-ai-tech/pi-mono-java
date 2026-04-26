package com.glmapper.coding.core.tools.policy;

import com.glmapper.coding.core.runtime.subagent.SubagentRole;
import com.glmapper.coding.core.runtime.subagent.WorkspaceScope;

public record ToolRuntimeContext(
        String tenantId,
        String namespace,
        String userId,
        String projectKey,
        String sessionId,
        String runId,
        String subagentId,
        SubagentRole agentRole,
        int depth,
        WorkspaceScope workspaceScope
) {
    public boolean isMainAgent() {
        return subagentId == null || subagentId.isBlank();
    }
}

