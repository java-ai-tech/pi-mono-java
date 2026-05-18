package com.glmapper.coding.core.execution;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface WorkspaceStorage {

    Path resolveLocal(String namespace, String sessionId);

    void prepareForRun(String namespace, String sessionId);

    CompletableFuture<Void> persistAfterRun(String namespace, String sessionId);

    void cleanupLocal(String namespace, String sessionId);
}
