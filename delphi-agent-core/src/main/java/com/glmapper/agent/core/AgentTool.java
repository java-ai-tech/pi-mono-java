package com.glmapper.agent.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface AgentTool {
    String name();

    String label();

    String description();

    Map<String, Object> parametersSchema();

    CompletableFuture<AgentToolResult> execute(
            String toolCallId,
            Map<String, Object> params,
            AgentToolUpdateCallback onUpdate,
            java.util.concurrent.CancellationException cancellation
    );
}
