package com.glmapper.agent.core;

import com.glmapper.ai.api.ToolCallContent;

import java.util.Map;

public record AfterToolCallContext(
        AgentAssistantMessage assistantMessage,
        ToolCallContent toolCall,
        Map<String, Object> args,
        AgentToolResult result,
        boolean isError,
        AgentContext context
) {
}
