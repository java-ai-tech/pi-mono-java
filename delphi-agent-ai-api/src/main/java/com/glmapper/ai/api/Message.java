package com.glmapper.ai.api;

public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
    String role();
    long timestamp();
}
