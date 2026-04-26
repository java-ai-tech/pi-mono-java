package com.glmapper.coding.core.runtime;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class RunQueueManager {

    private final ConcurrentHashMap<String, Queue<QueuedRun>> queues = new ConcurrentHashMap<>();

    public RunQueueDecision decide(AgentRunContext context, boolean hasActiveRun) {
        if (!hasActiveRun) {
            return RunQueueDecision.runNow();
        }
        RunQueueMode mode = context.queueMode() == null ? RunQueueMode.INTERRUPT : context.queueMode();
        return switch (mode) {
            case FOLLOWUP -> new RunQueueDecision(RunQueueDecisionType.ENQUEUE, "active_run_followup");
            case STEER -> new RunQueueDecision(RunQueueDecisionType.STEER, "active_run_steer");
            case DROP -> new RunQueueDecision(RunQueueDecisionType.DROP, "active_run_drop");
            case REJECT -> new RunQueueDecision(RunQueueDecisionType.REJECT, "active_run_reject");
            case INTERRUPT -> new RunQueueDecision(RunQueueDecisionType.INTERRUPT, "active_run_interrupt");
        };
    }

    public int enqueue(AgentRunContext context, RuntimeEventSink sink) {
        String key = queueKey(context.namespace(), context.sessionId());
        Queue<QueuedRun> queue = queues.computeIfAbsent(key, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(new QueuedRun(context, sink));
        return queue.size();
    }

    public Optional<QueuedRun> pollNext(String namespace, String sessionId) {
        String key = queueKey(namespace, sessionId);
        Queue<QueuedRun> queue = queues.get(key);
        if (queue == null) {
            return Optional.empty();
        }
        QueuedRun next = queue.poll();
        if (queue.isEmpty()) {
            queues.remove(key, queue);
        }
        return Optional.ofNullable(next);
    }

    public int queueSize(String namespace, String sessionId) {
        Queue<QueuedRun> queue = queues.get(queueKey(namespace, sessionId));
        return queue == null ? 0 : queue.size();
    }

    private String queueKey(String namespace, String sessionId) {
        return namespace + ":" + sessionId;
    }

    public record QueuedRun(AgentRunContext context, RuntimeEventSink sink) {
        public QueuedRun {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(sink, "sink");
        }
    }
}
