package com.glmapper.agent.core;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Serializes file mutations per-path so concurrent tool calls
 * targeting the same file execute in order, while operations
 * on different files run in parallel.
 */
public final class FileMutationQueue {

    private final ConcurrentHashMap<Path, CompletableFuture<Void>> fileQueues = new ConcurrentHashMap<>();

    /**
     * Enqueue an async operation for the given file path.
     * Operations on the same normalized path execute sequentially;
     * operations on different paths may execute concurrently.
     *
     * @param filePath  the target file path
     * @param operation supplier that produces the async operation
     * @param <T>       result type
     * @return a future that completes when the operation finishes
     */
    public <T> CompletableFuture<T> enqueue(Path filePath, Supplier<CompletableFuture<T>> operation) {
        Path key = filePath.toAbsolutePath().normalize();

        CompletableFuture<T> result = new CompletableFuture<>();

        fileQueues.compute(key, (k, prev) -> {
            CompletableFuture<Void> gate = (prev != null) ? prev : CompletableFuture.completedFuture(null);

            CompletableFuture<Void> next = gate
                    .thenCompose(ignored -> {
                        try {
                            return operation.get().thenAccept(value -> result.complete(value));
                        } catch (Throwable t) {
                            result.completeExceptionally(t);
                            return CompletableFuture.completedFuture(null);
                        }
                    })
                    .exceptionally(t -> {
                        result.completeExceptionally(t);
                        return null;
                    });

            return next;
        });

        // Clean up the map entry when the chain completes
        result.whenComplete((v, t) -> fileQueues.remove(key));

        return result;
    }

    /**
     * Returns the number of file paths currently tracked (for testing).
     */
    int activePathCount() {
        return fileQueues.size();
    }
}
