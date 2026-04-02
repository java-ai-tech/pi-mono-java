package com.glmapper.agent.core;

import com.glmapper.ai.api.ContentBlock;

import java.util.List;

public record AgentToolResultMessage(
        String toolCallId,
        String toolName,
        List<ContentBlock> content,
        Object details,
        boolean isError,
        long timestamp
) implements AgentMessage {
    @Override
    public String role() {
        return "toolResult";
    }
}
