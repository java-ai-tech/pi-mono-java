package com.glmapper.coding.core.domain;

import com.glmapper.agent.core.AgentMessage;

import java.util.List;

public record SessionStateSnapshot(
        String sessionId,
        String modelProvider,
        String modelId,
        String thinkingLevel,
        boolean streaming,
        String error,
        List<AgentMessage> messages
) {
}
