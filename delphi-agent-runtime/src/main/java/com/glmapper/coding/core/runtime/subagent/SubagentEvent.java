package com.glmapper.coding.core.runtime.subagent;

import java.time.Instant;
import java.util.Map;

public record SubagentEvent(
        String subagentId,
        String parentRunId,
        String name,
        Instant timestamp,
        Map<String, Object> payload
) {
}

