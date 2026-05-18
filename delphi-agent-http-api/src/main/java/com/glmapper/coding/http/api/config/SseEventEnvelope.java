package com.glmapper.coding.http.api.config;

import com.glmapper.coding.core.runtime.RuntimeEvent;

public record SseEventEnvelope(
        String sourceNodeId,
        RuntimeEvent event
) {
}
