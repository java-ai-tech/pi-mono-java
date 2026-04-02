package com.glmapper.agent.core;

import java.util.List;
import java.util.Map;

public final class ToolArgumentValidator {
    private ToolArgumentValidator() {
    }

    public static Map<String, Object> validate(Map<String, Object> schema, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        if (schema == null || schema.isEmpty()) {
            return safeArgs;
        }

        Object type = schema.get("type");
        if (type instanceof String t && !"object".equals(t)) {
            throw new IllegalArgumentException("Tool schema root type must be object");
        }

        Object requiredValue = schema.get("required");
        if (requiredValue instanceof List<?> requiredList) {
            for (Object key : requiredList) {
                if (!(key instanceof String requiredKey)) {
                    continue;
                }
                if (!safeArgs.containsKey(requiredKey) || safeArgs.get(requiredKey) == null) {
                    throw new IllegalArgumentException("Missing required parameter: " + requiredKey);
                }
            }
        }

        Object propertiesValue = schema.get("properties");
        if (propertiesValue instanceof Map<?, ?> rawProps) {
            for (Map.Entry<?, ?> entry : rawProps.entrySet()) {
                if (!(entry.getKey() instanceof String propName)) {
                    continue;
                }
                Object value = safeArgs.get(propName);
                if (value == null) {
                    continue;
                }
                if (!(entry.getValue() instanceof Map<?, ?> rawPropSchema)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> propSchema = (Map<String, Object>) rawPropSchema;
                validateType(propName, value, (String) propSchema.get("type"));
            }
        }

        return safeArgs;
    }

    private static void validateType(String name, Object value, String type) {
        if (type == null || type.isBlank()) {
            return;
        }
        boolean ok = switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };

        if (!ok) {
            throw new IllegalArgumentException("Invalid parameter type for '" + name + "': expected " + type);
        }
    }
}
