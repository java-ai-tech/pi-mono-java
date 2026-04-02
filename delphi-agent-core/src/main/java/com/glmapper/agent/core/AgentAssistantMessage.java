package com.glmapper.agent.core;

import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.StopReason;
import com.glmapper.ai.api.Usage;

import java.util.List;

public record AgentAssistantMessage(
        List<ContentBlock> content,
        String api,
        String provider,
        String model,
        Usage usage,
        StopReason stopReason,
        String errorMessage,
        String responseId,
        long timestamp
) implements AgentMessage {
    @Override
    public String role() {
        return "assistant";
    }
}
