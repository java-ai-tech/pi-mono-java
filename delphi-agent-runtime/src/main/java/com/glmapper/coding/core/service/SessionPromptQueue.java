package com.glmapper.coding.core.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Serializes prompt execution per session to prevent concurrent runs.
 */
@Component
public class SessionPromptQueue {
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<QueuedPrompt>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeRuns = new ConcurrentHashMap<>();

    /**
     * Submit an action to be executed serially for the given session.
     */
    public CompletableFuture<Void> submit(String sessionId, Supplier<CompletableFuture<Void>> action) {
        QueuedPrompt queued = new QueuedPrompt(action, new CompletableFuture<>());
        queues.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>()).add(queued);
        tryDrain(sessionId);
        return queued.result;
    }

    /**
     * Cancel all pending prompts for a session.
     */
    public void cancel(String sessionId) {
        ConcurrentLinkedQueue<QueuedPrompt> queue = queues.remove(sessionId);
        if (queue != null) {
            queue.forEach(q -> q.result.completeExceptionally(new RuntimeException("Session cancelled")));
        }
        CompletableFuture<Void> active = activeRuns.remove(sessionId);
        if (active != null) {
            active.completeExceptionally(new RuntimeException("Session cancelled"));
        }
    }

    private void tryDrain(String sessionId) {
        // Use putIfAbsent as CAS lock
        CompletableFuture<Void> sentinel = new CompletableFuture<>();
        if (activeRuns.putIfAbsent(sessionId, sentinel) != null) {
            // Another thread is draining
            return;
        }

        // We acquired the lock, drain the queue
        drainQueue(sessionId);
    }

    private void drainQueue(String sessionId) {
        ConcurrentLinkedQueue<QueuedPrompt> queue = queues.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            activeRuns.remove(sessionId);
            return;
        }

        QueuedPrompt next = queue.poll();
        if (next == null) {
            activeRuns.remove(sessionId);
            return;
        }

        try {
            CompletableFuture<Void> actionFuture = next.action.get();
            actionFuture.whenComplete((result, error) -> {
                if (error != null) {
                    next.result.completeExceptionally(error);
                } else {
                    next.result.complete(null);
                }
                // Remove active run and recurse
                activeRuns.remove(sessionId);
                tryDrain(sessionId);
            });
        } catch (Exception e) {
            next.result.completeExceptionally(e);
            activeRuns.remove(sessionId);
            tryDrain(sessionId);
        }
    }

    /**
     * Queued prompt holder.
     */
    record QueuedPrompt(Supplier<CompletableFuture<Void>> action, CompletableFuture<Void> result) {
    }
}
