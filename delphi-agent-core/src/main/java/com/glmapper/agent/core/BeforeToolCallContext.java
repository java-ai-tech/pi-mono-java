package com.glmapper.agent.core;

import com.glmapper.ai.api.ToolCallContent;

import java.util.Map;

public record BeforeToolCallContext(
        AgentAssistantMessage assistantMessage,
        ToolCallContent toolCall,
        Map<String, Object> args,
        AgentContext context
) {
}
