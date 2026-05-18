package com.glmapper.coding.core.execution;

import com.glmapper.coding.core.cluster.ClusterKeyRegistry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Component
@ConditionalOnProperty(prefix = "pi.execution.workspace-storage", name = "type", havingValue = "snapshot")
public class SnapshotWorkspaceStorage implements WorkspaceStorage {
    private static final Logger log = LoggerFactory.getLogger(SnapshotWorkspaceStorage.class);
    private static final String SNAPSHOT_KEY_TEMPLATE = "workspaces/%s/%s/snapshot.tar.gz";

    private final Path workspacesRoot;
    private final S3Client s3Client;
    private final String bucket;
    private final List<String> excludePaths;
    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ExecutorService persistExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SnapshotWorkspaceStorage(
            @Value("${pi.execution.workspaces-root:${PI_WORKSPACES_ROOT:workspaces}}") String workspacesRoot,
            @Value("${pi.execution.workspace-storage.snapshot.bucket}") String bucket,
            @Value("${pi.execution.workspace-storage.snapshot.exclude:.skills/,node_modules/,.git/,target/,build/,dist/}") String excludeCsv,
            S3Client s3Client,
            StringRedisTemplate redisTemplate,
            ClusterKeyRegistry keyRegistry) {
        this.workspacesRoot = Paths.get(workspacesRoot).toAbsolutePath();
        this.bucket = bucket;
        this.s3Client = s3Client;
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.excludePaths = Arrays.stream(excludeCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
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
        if (Files.exists(workspace) && !isEmpty(workspace)) {
            return;
        }

        String lockKey = keyRegistry.getPrefix() + ":workspace:lock:" + namespace + ":" + sessionId;
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey, lockValue, java.time.Duration.ofMinutes(5));
        if (Boolean.FALSE.equals(acquired)) {
            log.warn("Workspace recovery lock held by another node, skipping: ns={}, session={}", namespace, sessionId);
            try {
                Files.createDirectories(workspace);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create workspace: " + workspace, e);
            }
            return;
        }

        try {
            if (Files.exists(workspace) && !isEmpty(workspace)) {
                return;
            }
            Files.createDirectories(workspace);
            String s3Key = String.format(SNAPSHOT_KEY_TEMPLATE, namespace, sessionId);
            try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(s3Key).build())) {
                extractTarGz(response, workspace);
                log.info("Restored workspace from S3: ns={}, session={}, key={}", namespace, sessionId, s3Key);
            } catch (NoSuchKeyException e) {
                // first run for this session, no snapshot exists
            } catch (Exception e) {
                log.warn("Failed to restore workspace from S3: ns={}, session={}", namespace, sessionId, e);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare workspace: " + workspace, e);
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    @Override
    public CompletableFuture<Void> persistAfterRun(String namespace, String sessionId) {
        Path workspace = resolveLocal(namespace, sessionId);
        if (!Files.exists(workspace)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            Path tempArchive = null;
            try {
                tempArchive = Files.createTempFile("workspace-snapshot-", ".tar.gz");
                createTarGz(workspace, tempArchive);
                String s3Key = String.format(SNAPSHOT_KEY_TEMPLATE, namespace, sessionId);
                s3Client.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(s3Key).build(),
                        RequestBody.fromFile(tempArchive));
                log.info("Persisted workspace to S3: ns={}, session={}, key={}, size={}",
                        namespace, sessionId, s3Key, Files.size(tempArchive));
            } catch (Exception e) {
                log.warn("Failed to persist workspace to S3: ns={}, session={}", namespace, sessionId, e);
            } finally {
                if (tempArchive != null) {
                    try { Files.deleteIfExists(tempArchive); } catch (IOException ignored) {}
                }
            }
        }, persistExecutor);
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
            log.warn("Failed to cleanup local workspace: {}", workspace, e);
        }
    }

    private boolean isEmpty(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findFirst().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            String current = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(current)) {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception ignored) {}
    }

    private boolean isExcluded(Path workspace, Path entry) {
        String relative = workspace.relativize(entry).toString().replace('\\', '/');
        for (String exclude : excludePaths) {
            String normalized = exclude.endsWith("/") ? exclude : exclude + "/";
            if (relative.startsWith(normalized) || (relative + "/").startsWith(normalized)) {
                return true;
            }
        }
        return false;
    }

    private void createTarGz(Path workspace, Path archive) throws IOException {
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(archive));
             GZIPOutputStream gzos = new GZIPOutputStream(fos);
             TarArchiveOutputStream tarOs = new TarArchiveOutputStream(gzos)) {
            tarOs.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tarOs.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            try (var stream = Files.walk(workspace)) {
                stream.forEach(path -> {
                    if (path.equals(workspace)) return;
                    if (isExcluded(workspace, path)) return;
                    try {
                        String name = workspace.relativize(path).toString().replace('\\', '/');
                        if (Files.isDirectory(path)) {
                            name = name + "/";
                        }
                        TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), name);
                        tarOs.putArchiveEntry(entry);
                        if (Files.isRegularFile(path)) {
                            try (InputStream fis = new BufferedInputStream(Files.newInputStream(path))) {
                                fis.transferTo(tarOs);
                            }
                        }
                        tarOs.closeArchiveEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private void extractTarGz(InputStream input, Path workspace) throws IOException {
        try (GZIPInputStream gzis = new GZIPInputStream(input);
             TarArchiveInputStream tarIs = new TarArchiveInputStream(gzis)) {
            TarArchiveEntry entry;
            while ((entry = tarIs.getNextEntry()) != null) {
                Path target = workspace.resolve(entry.getName()).normalize();
                if (!target.startsWith(workspace)) {
                    log.warn("Skipping entry outside workspace: {}", entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(tarIs, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
}
