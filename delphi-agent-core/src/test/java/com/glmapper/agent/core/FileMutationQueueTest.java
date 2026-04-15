package com.glmapper.agent.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileMutationQueueTest {

    @Test
    void sameFile_executesSerially() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Path file = Paths.get("/tmp/test.txt");

        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        // First operation: waits for latch1, records "1"
        CompletableFuture<String> op1 = queue.enqueue(file, () ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        latch1.await(5, TimeUnit.SECONDS);
                        executionOrder.add(1);
                        return "op1";
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
        );

        // Second operation: waits for latch2, records "2"
        CompletableFuture<String> op2 = queue.enqueue(file, () ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        latch2.await(5, TimeUnit.SECONDS);
                        executionOrder.add(2);
                        return "op2";
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
        );

        // Release latch2 first (but op2 should still wait for op1)
        latch2.countDown();
        Thread.sleep(100);
        assertEquals(0, executionOrder.size(), "op2 should not execute before op1");

        // Release latch1, both should complete in order
        latch1.countDown();

        CompletableFuture.allOf(op1, op2).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1, 2), executionOrder, "Operations should execute in submission order");
        assertEquals("op1", op1.get());
        assertEquals("op2", op2.get());
    }

    @Test
    void differentFiles_executeConcurrently() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Path file1 = Paths.get("/tmp/file1.txt");
        Path file2 = Paths.get("/tmp/file2.txt");

        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(2);

        CompletableFuture<String> op1 = queue.enqueue(file1, () ->
                CompletableFuture.supplyAsync(() -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    startLatch.countDown();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    concurrentCount.decrementAndGet();
                    return "op1";
                })
        );

        CompletableFuture<String> op2 = queue.enqueue(file2, () ->
                CompletableFuture.supplyAsync(() -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    startLatch.countDown();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    concurrentCount.decrementAndGet();
                    return "op2";
                })
        );

        // Wait for both to start
        assertTrue(startLatch.await(5, TimeUnit.SECONDS), "Both operations should start");

        CompletableFuture.allOf(op1, op2).get(5, TimeUnit.SECONDS);

        assertEquals(2, maxConcurrent.get(), "Both operations should run concurrently");
        assertEquals("op1", op1.get());
        assertEquals("op2", op2.get());
    }

    @Test
    void normalizedPaths_treatedAsSame() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Path file1 = Paths.get("/tmp/./test.txt");
        Path file2 = Paths.get("/tmp/test.txt");

        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        CompletableFuture<String> op1 = queue.enqueue(file1, () ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        latch.await(5, TimeUnit.SECONDS);
                        executionOrder.add(1);
                        return "op1";
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
        );

        CompletableFuture<String> op2 = queue.enqueue(file2, () ->
                CompletableFuture.supplyAsync(() -> {
                    executionOrder.add(2);
                    return "op2";
                })
        );

        Thread.sleep(100);
        assertEquals(0, executionOrder.size(), "op2 should wait for op1");

        latch.countDown();
        CompletableFuture.allOf(op1, op2).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1, 2), executionOrder);
    }

    @Test
    void operationThrowsException_nextOperationStillExecutes() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Path file = Paths.get("/tmp/test.txt");

        CompletableFuture<String> op1 = queue.enqueue(file, () ->
                CompletableFuture.supplyAsync(() -> {
                    throw new RuntimeException("Intentional failure");
                })
        );

        CompletableFuture<String> op2 = queue.enqueue(file, () ->
                CompletableFuture.completedFuture("op2")
        );

        assertThrows(Exception.class, () -> op1.get(5, TimeUnit.SECONDS));
        assertEquals("op2", op2.get(5, TimeUnit.SECONDS));
    }

    @Test
    void mapCleanup_afterCompletion() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Path file = Paths.get("/tmp/test.txt");

        CompletableFuture<String> op = queue.enqueue(file, () ->
                CompletableFuture.completedFuture("done")
        );

        op.get(5, TimeUnit.SECONDS);

        // Give cleanup a moment to complete
        Thread.sleep(50);

        assertEquals(0, queue.activePathCount(), "Map should be cleaned up after operation completes");
    }

    @Test
    void multipleOperations_sameFile_maintainOrder() throws Exception {
        FileMutationQueue queue = new FileMutationQueue();
        Path file = Paths.get("/tmp/test.txt");

        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<?>[] futures = new CompletableFuture[5];
        for (int i = 0; i < 5; i++) {
            final int index = i;
            futures[i] = queue.enqueue(file, () ->
                    CompletableFuture.supplyAsync(() -> {
                        executionOrder.add(index);
                        return "op" + index;
                    })
            );
        }

        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(0, 1, 2, 3, 4), executionOrder);
    }
}
