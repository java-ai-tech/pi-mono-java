package com.glmapper.coding.core.runtime;

import java.util.Optional;

public interface RunQueueManager {

    RunQueueDecision decide(AgentRunContext context, boolean hasActiveRun);

    int enqueue(AgentRunContext context, RuntimeEventSink sink);

    Optional<QueuedRun> pollNext(String namespace, String sessionId);

    int queueSize(String namespace, String sessionId);

    record QueuedRun(AgentRunContext context, RuntimeEventSink sink) {
        public QueuedRun {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(sink, "sink");
        }
    }
}
