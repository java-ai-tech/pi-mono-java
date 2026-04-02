package com.glmapper.agent.core;

import com.glmapper.ai.api.ContentBlock;

import java.util.List;

public record AgentToolResult(List<ContentBlock> content, Object details) {
    public static AgentToolResult error(String message) {
        return new AgentToolResult(
                java.util.List.of(new com.glmapper.ai.api.TextContent(message, null)),
                java.util.Map.of("error", true)
        );
    }
}
