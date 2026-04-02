package com.glmapper.coding.core.domain;

public record SessionStats(
        String sessionId,
        int totalMessages,
        int userMessages,
        int assistantMessages,
        int toolResultMessages,
        int totalEntries,
        boolean streaming
) {
}
