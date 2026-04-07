package com.glmapper.ai.api;

public record ThinkingContent(String thinking, String thinkingSignature, boolean redacted) implements ContentBlock {
    @Override
    public String type() {
        return "thinking";
    }
}
