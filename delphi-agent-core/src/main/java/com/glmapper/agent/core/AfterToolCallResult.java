package com.glmapper.agent.core;

public record AfterToolCallResult(
        java.util.List<com.glmapper.ai.api.ContentBlock> content,
        Object details,
        Boolean isError
) {
}
