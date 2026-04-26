package com.glmapper.coding.core.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.glmapper.coding.core.tenant.TenantQuota;
import com.glmapper.coding.core.tenant.TenantQuotaManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Profile("!local-dev")
public class DockerIsolatedBackend implements ExecutionBackend {
    private static final Logger log = LoggerFactory.getLogger(DockerIsolatedBackend.class);

    private final Path workspacesRoot;
    private final String dockerImage;
    private final long cpuQuota;
    private final String memoryLimit;
    private final int pidsLimit;
    private final String network;
    private final boolean readOnlyRootfs;
    private final String tmpfsTmpSize;
    private final String tmpfsVarTmpSize;
    private final String tmpfsRunSize;

    @Autowired(required = false)
    private TenantQuotaManager tenantQuotaManager;

    @Autowired
    public DockerIsolatedBackend(
            @Value("${pi.execution.workspaces-root:${PI_WORKSPACES_ROOT:./workspaces}}") String workspacesRoot,
            @Value("${pi.execution.docker.image:pi-agent-sandbox:latest}") String dockerImage,
            @Value("${pi.execution.docker.cpu-quota:50000}") long cpuQuota,
            @Value("${pi.execution.docker.memory-limit:256m}") String memoryLimit,
            @Value("${pi.execution.docker.pids-limit:100}") int pidsLimit,
            @Value("${pi.execution.docker.network:bridge}") String network,
            @Value("${pi.execution.docker.read-only-rootfs:true}") boolean readOnlyRootfs,
            @Value("${pi.execution.docker.tmpfs.tmp:512m}") String tmpfsTmpSize,
            @Value("${pi.execution.docker.tmpfs.var-tmp:128m}") String tmpfsVarTmpSize,
            @Value("${pi.execution.docker.tmpfs.run:64m}") String tmpfsRunSize
    ) {
        this.workspacesRoot = Paths.get(workspacesRoot).toAbsolutePath();
        this.dockerImage = dockerImage;
        this.cpuQuota = cpuQuota;
        this.memoryLimit = memoryLimit;
        this.pidsLimit = pidsLimit;
        this.network = network;
        this.readOnlyRootfs = readOnlyRootfs;
        this.tmpfsTmpSize = tmpfsTmpSize;
        this.tmpfsVarTmpSize = tmpfsVarTmpSize;
        this.tmpfsRunSize = tmpfsRunSize;
        try {
            Files.createDirectories(this.workspacesRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspaces root: " + this.workspacesRoot, e);
        }
        verifyDockerAvailable();
    }

    /** Package-private constructor for unit tests — skips Docker availability check. */
    DockerIsolatedBackend(String workspacesRoot, String dockerImage, long cpuQuota, String memoryLimit, int pidsLimit,
                          String network, boolean readOnlyRootfs,
                          String tmpfsTmpSize, String tmpfsVarTmpSize, String tmpfsRunSize,
                          boolean skipDockerCheck) {
        this.workspacesRoot = Paths.get(workspacesRoot).toAbsolutePath();
        this.dockerImage = dockerImage;
        this.cpuQuota = cpuQuota;
        this.memoryLimit = memoryLimit;
        this.pidsLimit = pidsLimit;
        this.network = network;
        this.readOnlyRootfs = readOnlyRootfs;
        this.tmpfsTmpSize = tmpfsTmpSize;
        this.tmpfsVarTmpSize = tmpfsVarTmpSize;
        this.tmpfsRunSize = tmpfsRunSize;
        try {
            Files.createDirectories(this.workspacesRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspaces root: " + this.workspacesRoot, e);
        }
        if (!skipDockerCheck) {
            verifyDockerAvailable();
        }
    }

    private void verifyDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version").start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed || process.exitValue() != 0) {
                throw new RuntimeException("Docker is not available or not running");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify Docker availability", e);
        }
    }

    @Override
    public ExecutionResult execute(ExecutionContext context, String command, ExecutionOptions options) {
        validatePathSegment("namespace", context.namespace());
        validatePathSegment("sessionId", context.sessionId());
        Path workspace = getWorkspacePath(context.namespace(), context.sessionId());

        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace: " + workspace, e);
        }

        long startTime = System.currentTimeMillis();
        String[] dockerCmd = buildDockerCommand(context, command, options);

        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCmd);
            pb.environment().putAll(options.envVars());
            Process process = pb.start();

            boolean completed = process.waitFor(options.timeoutMs(), TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (!completed) {
                process.destroyForcibly();
                return new ExecutionResult(-1, "", "Execution timed out", duration, true, false);
            }

            String stdout = readStream(process.getInputStream(), options.maxOutputBytes());
            String stderr = readStream(process.getErrorStream(), options.maxOutputBytes());
            boolean truncated = stdout.length() >= options.maxOutputBytes()
                    || stderr.length() >= options.maxOutputBytes();

            return new ExecutionResult(process.exitValue(), stdout, stderr, duration, false, truncated);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Docker execution failed: namespace={} sessionId={}", context.namespace(), context.sessionId(), e);
            return new ExecutionResult(-1, "", "Docker execution failed: " + e.getMessage(), duration, false, false);
        }
    }

    String[] buildDockerCommand(ExecutionContext context, String command, ExecutionOptions options) {
        Path workspace = getWorkspacePath(context.namespace(), context.sessionId());

        long effectiveCpuQuota = this.cpuQuota;
        String effectiveMemoryLimit = this.memoryLimit;
        int effectivePidsLimit = this.pidsLimit;
        if (tenantQuotaManager != null) {
            TenantQuota tenantQuota = tenantQuotaManager.resolve(context.namespace());
            effectiveCpuQuota = tenantQuota.cpuQuota();
            effectiveMemoryLimit = tenantQuota.memoryLimit();
            effectivePidsLimit = tenantQuota.pidsLimit();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--network");
        cmd.add(network);
        cmd.add("--user");
        cmd.add("65534:65534");
        if (readOnlyRootfs) {
            cmd.add("--read-only");
        }
        cmd.add("--memory");
        cmd.add(effectiveMemoryLimit);
        cmd.add("--cpu-quota");
        cmd.add(String.valueOf(effectiveCpuQuota));
        cmd.add("--pids-limit");
        cmd.add(String.valueOf(effectivePidsLimit));
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,noexec,nosuid,size=" + tmpfsTmpSize);
        cmd.add("--tmpfs");
        cmd.add("/var/tmp:rw,size=" + tmpfsVarTmpSize);
        cmd.add("--tmpfs");
        cmd.add("/run:rw,size=" + tmpfsRunSize);
        cmd.add("-v");
        cmd.add(workspace.toAbsolutePath() + ":/workspace:rw");
        cmd.add("-w");
        cmd.add("/workspace");

        for (Map.Entry<String, String> env : options.envVars().entrySet()) {
            cmd.add("-e");
            cmd.add(env.getKey() + "=" + env.getValue());
        }

        cmd.add(dockerImage);
        cmd.add("bash");
        cmd.add("-c");
        cmd.add(command);
        return cmd.toArray(new String[0]);
    }

    @Override
    public String readFile(ExecutionContext context, String relativePath) {
        validatePathSegment("namespace", context.namespace());
        validatePathSegment("sessionId", context.sessionId());
        Path filePath = resolveAndValidatePath(context, relativePath);
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + relativePath, e);
        }
    }

    @Override
    public void writeFile(ExecutionContext context, String relativePath, String content) {
        validatePathSegment("namespace", context.namespace());
        validatePathSegment("sessionId", context.sessionId());
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
        return workspacesRoot.resolve(namespace).resolve(sessionId);
    }

    @Override
    public void cleanupSession(String namespace, String sessionId) {
        validatePathSegment("namespace", namespace);
        validatePathSegment("sessionId", sessionId);
        Path workspace = getWorkspacePath(namespace, sessionId);
        try {
            if (Files.exists(workspace)) {
                try (var stream = Files.walk(workspace)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            }
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
    }

    private Path resolveAndValidatePath(ExecutionContext context, String relativePath) {
        Path workspace = getWorkspacePath(context.namespace(), context.sessionId());
        Path resolved = workspace.resolve(relativePath).normalize();
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
}
