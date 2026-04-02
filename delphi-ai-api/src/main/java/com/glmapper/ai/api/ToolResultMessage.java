package com.glmapper.ai.api;

import java.util.List;

public record ToolResultMessage(
        String toolCallId,
        String toolName,
        List<ContentBlock> content,
        Object details,
        boolean isError,
        long timestamp
) implements Message {
    @Override
    public String role() {
        return "toolResult";
    }
}
