package com.glmapper.coding.core.runtime.subagent;

import java.time.Instant;
import java.util.Map;

public record SubagentResult(
        String subagentId,
        String parentRunId,
        SubagentRole role,
        SubagentStatus status,
        String summary,
        String errorMessage,
        Instant startedAt,
        Instant endedAt,
        Map<String, Object> details
) {
}

