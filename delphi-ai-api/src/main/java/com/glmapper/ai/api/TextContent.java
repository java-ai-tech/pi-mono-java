package com.glmapper.ai.api;

public record TextContent(String text, String textSignature) implements ContentBlock {
    @Override
    public String type() {
        return "text";
    }
}
