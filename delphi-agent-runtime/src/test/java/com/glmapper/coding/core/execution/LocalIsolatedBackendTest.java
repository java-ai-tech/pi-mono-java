package com.glmapper.coding.core.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalIsolatedBackendTest {

    @Test
    void shouldRejectPathTraversalInSessionId(@TempDir Path workspacesRoot) {
        LocalIsolatedBackend backend = new LocalIsolatedBackend(workspacesRoot.toString());
        ExecutionContext context = new ExecutionContext("tenant-a", "../escape", null);
        assertThrows(IllegalArgumentException.class, () ->
                backend.execute(context, "echo hello", ExecutionOptions.defaults()));
    }

    @Test
    void shouldRejectPathTraversalInNamespace(@TempDir Path workspacesRoot) {
        LocalIsolatedBackend backend = new LocalIsolatedBackend(workspacesRoot.toString());
        ExecutionContext context = new ExecutionContext("../escape", "s-1", null);
        assertThrows(IllegalArgumentException.class, () ->
                backend.execute(context, "echo hello", ExecutionOptions.defaults()));
    }

    @Test
    void shouldRejectSlashInSessionId(@TempDir Path workspacesRoot) {
        LocalIsolatedBackend backend = new LocalIsolatedBackend(workspacesRoot.toString());
        ExecutionContext context = new ExecutionContext("tenant-a", "foo/bar", null);
        assertThrows(IllegalArgumentException.class, () ->
                backend.execute(context, "echo hello", ExecutionOptions.defaults()));
    }

    @Test
    void shouldRejectNullByteInNamespace(@TempDir Path workspacesRoot) {
        LocalIsolatedBackend backend = new LocalIsolatedBackend(workspacesRoot.toString());
        ExecutionContext context = new ExecutionContext("tenant\0a", "s-1", null);
        assertThrows(IllegalArgumentException.class, () ->
                backend.execute(context, "echo hello", ExecutionOptions.defaults()));
    }

    @Test
    void shouldExecuteCommandInWorkspace(@TempDir Path workspacesRoot) {
        LocalIsolatedBackend backend = new LocalIsolatedBackend(workspacesRoot.toString());
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        ExecutionResult result = backend.execute(context, "echo hello", ExecutionOptions.defaults());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello"));
    }

    @Test
    void shouldIsolateWorkspacePerNamespaceAndSession(@TempDir Path workspacesRoot) throws Exception {
        LocalIsolatedBackend backend = new LocalIsolatedBackend(workspacesRoot.toString());

        // Write a file in tenant-a/s-1
        ExecutionContext ctxA = new ExecutionContext("tenant-a", "s-1", null);
        backend.writeFile(ctxA, "test.txt", "secret-a");

        // tenant-b/s-1 should not see it
        ExecutionContext ctxB = new ExecutionContext("tenant-b", "s-1", null);
        assertThrows(RuntimeException.class, () -> backend.readFile(ctxB, "test.txt"));

        // tenant-a/s-1 should see it
        String content = backend.readFile(ctxA, "test.txt");
        assertEquals("secret-a", content);
    }

    @Test
    void readFileShouldRejectPathTraversal(@TempDir Path workspacesRoot) throws Exception {
        LocalIsolatedBackend backend = new LocalIsolatedBackend(workspacesRoot.toString());
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        // Create workspace so readFile doesn't fail on missing dir
        backend.execute(context, "echo init", ExecutionOptions.defaults());
        assertThrows(SecurityException.class, () -> backend.readFile(context, "../../etc/passwd"));
    }
}
