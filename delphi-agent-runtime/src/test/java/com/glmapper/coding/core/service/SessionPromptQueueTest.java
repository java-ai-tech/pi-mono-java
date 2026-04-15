package com.glmapper.coding.core.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class SessionPromptQueueTest {
    private final SessionPromptQueue queue = new SessionPromptQueue();

    @Test
    void shouldExecuteActionsSerially() throws ExecutionException, InterruptedException, TimeoutException {
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<Void> f1 = queue.submit("s-1", () -> {
            executionOrder.add(1);
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> f2 = queue.submit("s-1", () -> {
            executionOrder.add(2);
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> f3 = queue.submit("s-1", () -> {
            executionOrder.add(3);
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture.allOf(f1, f2, f3).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1, 2, 3), executionOrder);
    }

    @Test
    void shouldExecuteDifferentSessionsConcurrently() throws ExecutionException, InterruptedException, TimeoutException {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<Void> f1 = queue.submit("s-1", () -> {
            executionOrder.add("s-1");
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> f2 = queue.submit("s-2", () -> {
            executionOrder.add("s-2");
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS);

        assertTrue(executionOrder.contains("s-1"));
        assertTrue(executionOrder.contains("s-2"));
    }

    @Test
    void shouldHandleAsyncActions() throws ExecutionException, InterruptedException, TimeoutException {
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<Void> f1 = queue.submit("s-1", () -> {
            CompletableFuture<Void> async = new CompletableFuture<>();
            // Simulate async completion
            CompletableFuture.runAsync(() -> {
                executionOrder.add(1);
                async.complete(null);
            });
            return async;
        });

        CompletableFuture<Void> f2 = queue.submit("s-1", () -> {
            executionOrder.add(2);
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1, 2), executionOrder);
    }

    @Test
    void cancelShouldCompleteExceptionally() {
        CompletableFuture<Void> blocker = new CompletableFuture<>();

        // Submit a blocking action
        queue.submit("s-1", () -> blocker);

        // Submit a pending action
        CompletableFuture<Void> pending = queue.submit("s-1", () ->
                CompletableFuture.completedFuture(null)
        );

        // Cancel the session
        queue.cancel("s-1");

        // Pending future should complete exceptionally
        assertTrue(pending.isCompletedExceptionally());
    }

    @Test
    void shouldPropagateActionErrors() {
        CompletableFuture<Void> result = queue.submit("s-1", () -> {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("test error"));
            return failed;
        });

        assertTrue(result.isCompletedExceptionally());
        assertThrows(ExecutionException.class, result::get);
    }

    @Test
    void shouldContinueDrainingAfterError() throws ExecutionException, InterruptedException, TimeoutException {
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        // First action fails
        CompletableFuture<Void> f1 = queue.submit("s-1", () -> {
            executionOrder.add(1);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("fail"));
            return failed;
        });

        // Second action should still execute
        CompletableFuture<Void> f2 = queue.submit("s-1", () -> {
            executionOrder.add(2);
            return CompletableFuture.completedFuture(null);
        });

        // Wait for f2 to complete
        f2.get(5, TimeUnit.SECONDS);

        assertTrue(f1.isCompletedExceptionally());
        assertFalse(f2.isCompletedExceptionally());
        assertEquals(List.of(1, 2), executionOrder);
    }
}
