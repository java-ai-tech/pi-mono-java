package com.glmapper.agent.core;

public sealed interface AgentMessage permits AgentUserMessage, AgentAssistantMessage, AgentToolResultMessage, AgentCustomMessage {
    String role();

    long timestamp();
}
