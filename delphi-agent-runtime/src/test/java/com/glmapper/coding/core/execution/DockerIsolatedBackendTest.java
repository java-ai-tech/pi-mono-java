package com.glmapper.coding.core.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DockerIsolatedBackendTest {

    private DockerIsolatedBackend createBackend(Path workspacesRoot) {
        return new DockerIsolatedBackend(
                workspacesRoot.toString(),
                "ubuntu:22.04",
                50000L,
                "256m",
                100,
                true // skip Docker check in tests
        );
    }

    @Test
    void shouldBuildDockerRunCommand(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot);
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        String[] cmd = backend.buildDockerCommand(context, "echo hello", ExecutionOptions.defaults());

        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("--network none"), "should disable network");
        assertTrue(joined.contains("--user 65534:65534"), "should run as nobody");
        assertTrue(joined.contains("--memory 256m"), "should limit memory");
        assertTrue(joined.contains("--cpu-quota 50000"), "should limit CPU");
        assertTrue(joined.contains("--pids-limit 100"), "should limit pids");
        assertTrue(joined.contains("--read-only"), "should be read-only rootfs");
        assertTrue(joined.contains(workspacesRoot.resolve("tenant-a").resolve("s-1").toString()),
                "should mount workspace");
        assertTrue(joined.contains("echo hello"), "should contain the command");
    }

    @Test
    void shouldRejectPathTraversalInSessionId(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot);
        ExecutionContext context = new ExecutionContext("tenant-a", "../escape", null);
        assertThrows(IllegalArgumentException.class, () ->
                backend.execute(context, "echo hello", ExecutionOptions.defaults()));
    }

    @Test
    void shouldRejectPathTraversalInNamespace(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot);
        ExecutionContext context = new ExecutionContext("../escape", "s-1", null);
        assertThrows(IllegalArgumentException.class, () ->
                backend.execute(context, "echo hello", ExecutionOptions.defaults()));
    }

    @Test
    void shouldIncludeEnvVarsInCommand(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot);
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        ExecutionOptions options = new ExecutionOptions(30000, 1024 * 1024, Map.of("FOO", "bar"));
        String[] cmd = backend.buildDockerCommand(context, "env", options);

        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("-e FOO=bar"), "should pass env vars");
    }
}
