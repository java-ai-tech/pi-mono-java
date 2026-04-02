package com.glmapper.agent.core;

import java.util.List;

public record AgentContext(String systemPrompt, List<AgentMessage> messages, List<AgentTool> tools) {
}
