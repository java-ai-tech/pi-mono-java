package com.glmapper.ai.api;

import java.util.List;

public record Context(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
}
