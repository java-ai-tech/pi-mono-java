package com.glmapper.coding.core.cluster;

public record SubagentCommandMessage(
        String commandId,
        String type,
        String subagentId,
        String reason,
        String requestedByNodeId
) {
    public static final String TYPE_ABORT = "abort";
}
