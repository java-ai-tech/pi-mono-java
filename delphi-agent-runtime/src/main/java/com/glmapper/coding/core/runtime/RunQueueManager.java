package com.glmapper.coding.core.runtime;

import java.util.Optional;

public interface RunQueueManager {

    RunQueueDecision decide(AgentRunContext context, boolean hasActiveRun);

    int enqueue(AgentRunContext context, RuntimeEventSink sink);

    Optional<PolledRun> pollNext(String namespace, String sessionId);

    /**
     * Acknowledge that a polled run has been successfully scheduled.
     * After ack, the run is permanently removed from the processing set.
     */
    void ack(String namespace, String sessionId, PolledRun polledRun);

    /**
     * Return a polled run to the front of the queue (or processing failure handling).
     * Used when scheduleRun fails (admission rejected, quota exceeded, etc.).
     */
    void requeue(String namespace, String sessionId, PolledRun polledRun);

    int queueSize(String namespace, String sessionId);

    /**
     * Polled run: includes context, sink, and an opaque token for ack/requeue.
     */
    record PolledRun(AgentRunContext context, RuntimeEventSink sink, String token) {
        public PolledRun {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(sink, "sink");
        }
    }

    /**
     * Legacy alias for backward compatibility.
     */
    record QueuedRun(AgentRunContext context, RuntimeEventSink sink) {
        public QueuedRun {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(sink, "sink");
        }
    }
}
