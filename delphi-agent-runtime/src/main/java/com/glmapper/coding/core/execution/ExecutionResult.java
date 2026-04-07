package com.glmapper.coding.core.execution;

public record ExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        boolean timeout,
        boolean truncated
) {
    public boolean isSuccess() {
        return exitCode == 0 && !timeout;
    }
}
