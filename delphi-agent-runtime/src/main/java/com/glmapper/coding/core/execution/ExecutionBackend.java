package com.glmapper.coding.core.execution;

import java.nio.file.Path;
import java.util.Map;

/**
 * Abstraction for isolated execution environments.
 * Implementations provide namespace-level isolation for command execution and file operations.
 */
public interface ExecutionBackend {

    /**
     * Execute a command in the isolated environment for the given namespace and session.
     *
     * @param context execution context containing namespace, sessionId, and other metadata
     * @param command the command to execute
     * @param options execution options (timeout, env vars, etc.)
     * @return execution result
     */
    ExecutionResult execute(ExecutionContext context, String command, ExecutionOptions options);

    /**
     * Read file content from the namespace workspace.
     */
    String readFile(ExecutionContext context, String relativePath);

    /**
     * Write file content to the namespace workspace.
     */
    void writeFile(ExecutionContext context, String relativePath, String content);

    /**
     * Get the workspace root path for a namespace and session.
     */
    Path getWorkspacePath(String namespace, String sessionId);

    /**
     * Clean up workspace for a session.
     */
    void cleanupSession(String namespace, String sessionId);
}
