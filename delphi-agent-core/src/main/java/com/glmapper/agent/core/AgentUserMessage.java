package com.glmapper.agent.core;

import com.glmapper.ai.api.ContentBlock;

import java.util.List;

public record AgentUserMessage(List<ContentBlock> content, long timestamp) implements AgentMessage {
    @Override
    public String role() {
        return "user";
    }
}
