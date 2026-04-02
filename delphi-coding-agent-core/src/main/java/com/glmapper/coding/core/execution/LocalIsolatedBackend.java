package com.glmapper.coding.core.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Component
@Profile("local-dev")
public class LocalIsolatedBackend implements ExecutionBackend {
    private static final Logger log = LoggerFactory.getLogger(LocalIsolatedBackend.class);

    private final Path workspacesRoot;

    public LocalIsolatedBackend(
            @Value("${pi.execution.workspaces-root:${PI_WORKSPACES_ROOT:./workspaces}}") String workspacesRoot
    ) {
        this.workspacesRoot = Paths.get(workspacesRoot).toAbsolutePath();
        try {
            Files.createDirectories(this.workspacesRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspaces root: " + this.workspacesRoot, e);
        }
    }

    @Override
    public ExecutionResult execute(ExecutionContext context, String command, ExecutionOptions options) {
        validateContext(context);
        Path workspace = getWorkspacePath(context.namespace(), context.sessionId());

        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace: " + workspace, e);
        }

        long startTime = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(workspace.toFile());
        pb.environment().putAll(options.envVars());

        try {
            Process process = pb.start();
            boolean completed = process.waitFor(options.timeoutMs(), TimeUnit.MILLISECONDS);

            String stdout = readStream(process.getInputStream(), options.maxOutputBytes());
            String stderr = readStream(process.getErrorStream(), options.maxOutputBytes());
            long duration = System.currentTimeMillis() - startTime;

            if (!completed) {
                process.destroyForcibly();
                return new ExecutionResult(-1, stdout, stderr, duration, true, false);
            }

            boolean truncated = stdout.length() >= options.maxOutputBytes()
                    || stderr.length() >= options.maxOutputBytes();

            return new ExecutionResult(
                    process.exitValue(),
                    stdout,
                    stderr,
                    duration,
                    false,
                    truncated
            );
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return new ExecutionResult(-1, "", e.getMessage(), duration, false, false);
        }
    }

    @Override
    public String readFile(ExecutionContext context, String relativePath) {
        validateContext(context);
        Path filePath = resolveAndValidatePath(context, relativePath);
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + relativePath, e);
        }
    }

    @Override
    public void writeFile(ExecutionContext context, String relativePath, String content) {
        validateContext(context);
        Path filePath = resolveAndValidatePath(context, relativePath);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + relativePath, e);
        }
    }

    @Override
    public Path getWorkspacePath(String namespace, String sessionId) {
        validatePathSegment("namespace", namespace);
        validatePathSegment("sessionId", sessionId);
        return workspacesRoot.resolve(namespace).resolve(sessionId);
    }

    @Override
    public void cleanupSession(String namespace, String sessionId) {
        validatePathSegment("namespace", namespace);
        validatePathSegment("sessionId", sessionId);
        Path workspace = getWorkspacePath(namespace, sessionId);
        try {
            if (Files.exists(workspace)) {
                deleteRecursively(workspace);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup workspace: {}", workspace, e);
        }
    }

    private void validateContext(ExecutionContext context) {
        if (context.namespace() == null || context.namespace().isBlank()) {
            throw new IllegalArgumentException("Namespace is required");
        }
        if (context.sessionId() == null || context.sessionId().isBlank()) {
            throw new IllegalArgumentException("SessionId is required");
        }
        // Prevent path traversal in namespace
        validatePathSegment("namespace", context.namespace());
        // Prevent path traversal in sessionId
        validatePathSegment("sessionId", context.sessionId());
    }

    private void validatePathSegment(String field, String value) {
        if (value.contains("..") || value.contains("/") || value.contains("\\")
                || value.contains("\0")) {
            throw new IllegalArgumentException("Invalid " + field + ": path traversal characters detected");
        }
        // After resolve, the workspace must still be under workspacesRoot
        Path resolved = workspacesRoot.resolve(value).normalize();
        if (!resolved.startsWith(workspacesRoot)) {
            throw new IllegalArgumentException("Invalid " + field + ": escapes workspace root");
        }
    }

    private Path resolveAndValidatePath(ExecutionContext context, String relativePath) {
        Path workspace = getWorkspacePath(context.namespace(), context.sessionId());
        Path resolved = workspace.resolve(relativePath).normalize();

        // Ensure the resolved path is within the workspace
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }

        return resolved;
    }

    private String readStream(java.io.InputStream stream, int maxBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            int total = 0;
            while ((read = reader.read(buffer)) != -1 && total < maxBytes) {
                int toAppend = Math.min(read, maxBytes - total);
                sb.append(buffer, 0, toAppend);
                total += toAppend;
            }
        }
        return sb.toString();
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        deleteRecursively(child);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
