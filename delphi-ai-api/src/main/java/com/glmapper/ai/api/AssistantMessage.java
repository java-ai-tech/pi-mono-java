package com.glmapper.ai.api;

import java.util.List;

public record AssistantMessage(
        List<ContentBlock> content,
        String api,
        String provider,
        String model,
        Usage usage,
        StopReason stopReason,
        String errorMessage,
        String responseId,
        long timestamp
) implements Message {
    @Override
    public String role() {
        return "assistant";
    }
}
