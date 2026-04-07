package com.glmapper.coding.core.rpc;

public record RpcSessionState(
        String sessionId,
        String sessionName,
        String provider,
        String modelId,
        String thinkingLevel,
        boolean isStreaming,
        boolean isCompacting,
        String steeringMode,
        String followUpMode,
        boolean autoCompactionEnabled,
        int messageCount,
        int pendingMessageCount
) {
}
