package com.glmapper.ai.api;

public sealed interface ContentBlock permits TextContent, ThinkingContent, ImageContent, ToolCallContent {
    String type();
}
