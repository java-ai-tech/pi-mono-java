package com.glmapper.coding.core.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(prefix = "pi.execution.workspace-storage", name = "type",
        havingValue = "local", matchIfMissing = true)
public class LocalWorkspaceStorage implements WorkspaceStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalWorkspaceStorage.class);

    private final Path workspacesRoot;

    public LocalWorkspaceStorage(
            @Value("${pi.execution.workspaces-root:${PI_WORKSPACES_ROOT:workspaces}}") String workspacesRoot) {
        this.workspacesRoot = Paths.get(workspacesRoot).toAbsolutePath();
        try {
            Files.createDirectories(this.workspacesRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspaces root: " + this.workspacesRoot, e);
        }
    }

    @Override
    public Path resolveLocal(String namespace, String sessionId) {
        validatePathSegment("namespace", namespace);
        validatePathSegment("sessionId", sessionId);
        return workspacesRoot.resolve(namespace).resolve(sessionId);
    }

    @Override
    public void prepareForRun(String namespace, String sessionId) {
        Path workspace = resolveLocal(namespace, sessionId);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare workspace: " + workspace, e);
        }
    }

    @Override
    public CompletableFuture<Void> persistAfterRun(String namespace, String sessionId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void cleanupLocal(String namespace, String sessionId) {
        Path workspace = resolveLocal(namespace, sessionId);
        if (!Files.exists(workspace)) return;
        try (var stream = Files.walk(workspace)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Failed to cleanup workspace: {}", workspace, e);
        }
    }

    private void validatePathSegment(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.contains("..") || value.contains("/") || value.contains("\\") || value.contains("\0")) {
            throw new IllegalArgumentException("Invalid " + field + ": path traversal characters detected");
        }
        Path resolved = workspacesRoot.resolve(value).normalize();
        if (!resolved.startsWith(workspacesRoot)) {
            throw new IllegalArgumentException("Invalid " + field + ": escapes workspace root");
        }
    }
}
