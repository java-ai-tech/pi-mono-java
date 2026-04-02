package com.glmapper.ai.api;

import java.util.Map;

public record ToolCallContent(
        String id,
        String name,
        Map<String, Object> arguments,
        String thoughtSignature
) implements ContentBlock {
    @Override
    public String type() {
        return "toolCall";
    }
}
