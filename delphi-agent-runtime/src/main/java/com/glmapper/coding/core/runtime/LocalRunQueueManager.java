package com.glmapper.coding.core.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalRunQueueManager implements RunQueueManager {

    private final ConcurrentHashMap<String, Deque<PolledRun>> queues = new ConcurrentHashMap<>();

    @Override
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

    @Override
    public int enqueue(AgentRunContext context, RuntimeEventSink sink) {
        String key = queueKey(context.namespace(), context.sessionId());
        Deque<PolledRun> queue = queues.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        queue.addLast(new PolledRun(context, sink, UUID.randomUUID().toString()));
        return queue.size();
    }

    @Override
    public Optional<PolledRun> pollNext(String namespace, String sessionId) {
        String key = queueKey(namespace, sessionId);
        Deque<PolledRun> queue = queues.get(key);
        if (queue == null) {
            return Optional.empty();
        }
        PolledRun next = queue.pollFirst();
        if (queue.isEmpty()) {
            queues.remove(key, queue);
        }
        return Optional.ofNullable(next);
    }

    @Override
    public void ack(String namespace, String sessionId, PolledRun polledRun) {
        // Local implementation: ack is a no-op (already removed by pollNext)
    }

    @Override
    public void requeue(String namespace, String sessionId, PolledRun polledRun) {
        String key = queueKey(namespace, sessionId);
        Deque<PolledRun> queue = queues.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        queue.addFirst(polledRun);
    }

    @Override
    public int queueSize(String namespace, String sessionId) {
        Deque<PolledRun> queue = queues.get(queueKey(namespace, sessionId));
        return queue == null ? 0 : queue.size();
    }

    private String queueKey(String namespace, String sessionId) {
        return namespace + ":" + sessionId;
    }
}
