package com.glmapper.ai.api;

import java.util.List;

public record UserMessage(List<ContentBlock> content, long timestamp) implements Message {
    @Override
    public String role() {
        return "user";
    }
}
