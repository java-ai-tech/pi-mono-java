package com.glmapper.ai.api;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parametersSchema) {
}
