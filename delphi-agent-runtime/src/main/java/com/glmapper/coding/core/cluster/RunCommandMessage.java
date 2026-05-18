package com.glmapper.coding.core.cluster;

public record RunCommandMessage(
        String commandId,
        String type,
        String namespace,
        String sessionId,
        String runId,
        String payload,
        String requestedByNodeId
) {
    public static final String TYPE_ABORT = "abort";
    public static final String TYPE_STEER = "steer";
}
