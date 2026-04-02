package com.glmapper.ai.api;

public record ImageContent(String data, String mimeType) implements ContentBlock {
    @Override
    public String type() {
        return "image";
    }
}
