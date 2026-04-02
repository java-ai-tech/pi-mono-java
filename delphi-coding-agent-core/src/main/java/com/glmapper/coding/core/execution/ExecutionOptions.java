package com.glmapper.coding.core.execution;

import java.util.Map;

public record ExecutionOptions(
        long timeoutMs,
        int maxOutputBytes,
        Map<String, String> envVars
) {
    public static ExecutionOptions defaults() {
        return new ExecutionOptions(30000, 1024 * 1024, Map.of());
    }
}
