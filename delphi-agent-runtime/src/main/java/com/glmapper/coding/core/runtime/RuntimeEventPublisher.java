package com.glmapper.coding.core.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class RuntimeEventPublisher {

    public void emit(RuntimeEventSink sink, AgentRunContext context, String name, Map<String, Object> payload) {
        sink.emit(new RuntimeEvent(
                "evt_" + UUID.randomUUID(),
                name,
                context.runId(),
                context.tenantId(),
                context.namespace(),
                context.userId(),
                context.sessionId(),
                null,
                Instant.now(),
                payload
        ));
    }

    public void emitSubagent(RuntimeEventSink sink,
                             AgentRunContext context,
                             String subagentId,
                             String name,
                             Map<String, Object> payload) {
        sink.emit(new RuntimeEvent(
                "evt_" + UUID.randomUUID(),
                name,
                context.runId(),
                context.tenantId(),
                context.namespace(),
                context.userId(),
                context.sessionId(),
                subagentId,
                Instant.now(),
                payload
        ));
    }
}
