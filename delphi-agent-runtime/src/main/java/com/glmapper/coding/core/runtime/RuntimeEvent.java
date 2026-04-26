package com.glmapper.coding.core.runtime;

import java.time.Instant;
import java.util.Map;

public record RuntimeEvent(
        String eventId,
        String name,
        String runId,
        String tenantId,
        String namespace,
        String userId,
        String sessionId,
        String subagentId,
        Instant timestamp,
        Map<String, Object> payload
) {
}
