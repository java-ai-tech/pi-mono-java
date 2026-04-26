package com.glmapper.coding.core.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DockerIsolatedBackendTest {

    private DockerIsolatedBackend createBackend(Path workspacesRoot) {
        return createBackend(workspacesRoot, "pi-agent-sandbox:latest", "bridge", true,
                "512m", "128m", "64m");
    }

    private DockerIsolatedBackend createBackend(Path workspacesRoot, String image, String network,
                                                boolean readOnly, String tmp, String varTmp, String run) {
        return new DockerIsolatedBackend(
                workspacesRoot.toString(),
                image,
                50000L,
                "256m",
                100,
                network,
                readOnly,
                tmp,
                varTmp,
                run,
                true // skip Docker check in tests
        );
    }

    @Test
    void shouldBuildDockerRunCommand(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot);
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        String[] cmd = backend.buildDockerCommand(context, "echo hello", ExecutionOptions.defaults());

        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("--network bridge"), "should default to bridge network");
        assertTrue(joined.contains("--user 65534:65534"), "should run as nobody");
        assertTrue(joined.contains("--memory 256m"), "should limit memory");
        assertTrue(joined.contains("--cpu-quota 50000"), "should limit CPU");
        assertTrue(joined.contains("--pids-limit 100"), "should limit pids");
        assertTrue(joined.contains("--read-only"), "should be read-only rootfs by default");
        assertTrue(joined.contains("/tmp:rw,noexec,nosuid,size=512m"), "should mount /tmp tmpfs");
        assertTrue(joined.contains("/var/tmp:rw,size=128m"), "should mount /var/tmp tmpfs");
        assertTrue(joined.contains("/run:rw,size=64m"), "should mount /run tmpfs");
        assertTrue(joined.contains("pi-agent-sandbox:latest"), "should use sandbox image");
        assertTrue(joined.contains(workspacesRoot.resolve("tenant-a").resolve("s-1").toString()),
                "should mount workspace");
        assertTrue(joined.contains("echo hello"), "should contain the command");
    }

    @Test
    void shouldDisableNetworkWhenConfigured(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot,
                "pi-agent-sandbox:latest", "none", true, "512m", "128m", "64m");
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        String[] cmd = backend.buildDockerCommand(context, "echo hi", ExecutionOptions.defaults());

        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("--network none"), "should pass --network none");
        assertFalse(joined.contains("--network bridge"), "should not default to bridge");
    }

    @Test
    void shouldOmitReadOnlyWhenDisabled(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot,
                "pi-agent-sandbox:latest", "bridge", false, "512m", "128m", "64m");
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        String[] cmd = backend.buildDockerCommand(context, "echo hi", ExecutionOptions.defaults());

        String joined = String.join(" ", cmd);
        assertFalse(joined.contains("--read-only"), "should omit --read-only when disabled");
    }

    @Test
    void shouldUseConfiguredTmpfsSizes(@TempDir Path workspacesRoot) {
        DockerIsolatedBackend backend = createBackend(workspacesRoot,
                "pi-agent-sandbox:latest", "bridge", true, "1g", "256m", "32m");
        ExecutionContext context = new ExecutionContext("tenant-a", "s-1", null);
        String[] cmd = backend.buildDockerCommand(context, "echo hi", ExecutionOptions.defaults());

        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("/tmp:rw,noexec,nosuid,size=1g"), "should reflect /tmp size");
        assertTrue(joined.contains("/var/tmp:rw,size=256m"), "should reflect /var/tmp size");
        assertTrue(joined.contains("/run:rw,size=32m"), "should reflect /run size");
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
