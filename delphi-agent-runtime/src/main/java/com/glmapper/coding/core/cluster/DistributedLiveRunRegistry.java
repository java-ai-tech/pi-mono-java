package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import com.glmapper.coding.core.runtime.AgentRunContext;
import com.glmapper.coding.core.runtime.LiveRunRegistry;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import com.glmapper.coding.core.tenant.TenantQuota;
import com.glmapper.coding.core.tenant.TenantQuotaManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class DistributedLiveRunRegistry implements LiveRunRegistry {
    private static final Logger log = LoggerFactory.getLogger(DistributedLiveRunRegistry.class);

    private final Map<String, ActiveRun> localHandles = new ConcurrentHashMap<>();
    private final RunAdmissionController admissionController;
    private final RunCommandDispatcher commandDispatcher;
    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final TenantQuotaManager tenantQuotaManager;
    private final ScheduledExecutorService renewExecutor;

    public DistributedLiveRunRegistry(RunAdmissionController admissionController,
                                      RunCommandDispatcher commandDispatcher,
                                      StringRedisTemplate redisTemplate,
                                      ClusterKeyRegistry keyRegistry,
                                      TenantQuotaManager tenantQuotaManager,
                                      PiAgentProperties properties) {
        this.admissionController = admissionController;
        this.commandDispatcher = commandDispatcher;
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.tenantQuotaManager = tenantQuotaManager;
        this.renewExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "run-lease-renewal");
            t.setDaemon(true);
            return t;
        });
        long ttlMs = properties.cluster().run().maxTtlMs();
        long intervalMs = Math.max(1_000L, Math.min(30_000L, ttlMs / 3));
        this.renewExecutor.scheduleAtFixedRate(this::renewLocalRuns,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void register(ActiveRun run) {
        TenantQuota quota = tenantQuotaManager.resolve(run.namespace());
        int maxTenant = quota.maxConcurrentSessions();
        int maxUser = Math.max(1, maxTenant / 2);

        RunAdmissionController.AdmissionResult result =
                admissionController.acquire(run.context(), maxTenant, maxUser);

        switch (result) {
            case OK -> localHandles.put(run.runId(), run);
            case SESSION_BUSY -> throw new AdmissionRejectedException("session already has active run");
            case TENANT_QUOTA_EXCEEDED -> throw new AdmissionRejectedException("tenant concurrent run quota exceeded");
            case USER_QUOTA_EXCEEDED -> throw new AdmissionRejectedException("user concurrent run quota exceeded");
        }
    }

    @Override
    public void complete(String runId) {
        ActiveRun run = localHandles.remove(runId);
        if (run != null) {
            admissionController.release(runId, run.namespace(), run.sessionId(), run.userId());
        } else {
            log.warn("complete() called for non-local run: {}", runId);
        }
    }

    @Override
    public Optional<ActiveRun> findBySession(String namespace, String sessionId) {
        // First check local handles
        Optional<ActiveRun> local = localHandles.values().stream()
                .filter(r -> namespace.equals(r.namespace()) && sessionId.equals(r.sessionId()))
                .findFirst();
        if (local.isPresent()) {
            return local;
        }

        // If not found locally, check Redis for remote run
        String bySessionKey = keyRegistry.runBySessionKey(namespace, sessionId);
        String runId = redisTemplate.opsForValue().get(bySessionKey);
        if (runId == null) {
            return Optional.empty();
        }

        // Found a remote run, fetch its metadata
        String activeRunKey = keyRegistry.activeRunKey(runId);
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(activeRunKey);
        if (fields.isEmpty()) {
            return Optional.empty();
        }

        // Create a stub ActiveRun that delegates abort/steer to remote node via command dispatcher
        String userId = (String) fields.get("userId");

        return Optional.of(new ActiveRun(
                runId,
                namespace,
                sessionId,
                namespace, // tenantId = namespace
                userId == null || userId.isEmpty() ? null : userId,
                null, // context not available for remote runs
                null, // sink not available for remote runs
                reason -> commandDispatcher.sendAbort(namespace, sessionId, reason),
                text -> commandDispatcher.sendSteer(namespace, sessionId, text)
        ));
    }

    @Override
    public Optional<ActiveRun> findByRunId(String runId) {
        return Optional.ofNullable(localHandles.get(runId));
    }

    @Override
    public int activeCountByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return 0;
        return activeRunCount(keyRegistry.tenantActiveCountKey(tenantId));
    }

    @Override
    public int activeCountByUser(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) return 0;
        return activeRunCount(keyRegistry.userActiveCountKey(tenantId, userId));
    }

    @Override
    public boolean abortSession(String namespace, String sessionId, String reason) {
        // findBySession returns either local handle or remote stub; both delegate abort correctly
        Optional<ActiveRun> run = findBySession(namespace, sessionId);
        if (run.isPresent()) {
            run.get().abort(reason);
            return true;
        }
        return false;
    }

    @Override
    public boolean steerSession(String namespace, String sessionId, String text) {
        // findBySession returns either local handle or remote stub; both delegate steer correctly
        Optional<ActiveRun> run = findBySession(namespace, sessionId);
        if (run.isPresent()) {
            run.get().steer(text);
            return true;
        }
        return false;
    }

    public static class AdmissionRejectedException extends RuntimeException {
        public AdmissionRejectedException(String message) {
            super(message);
        }
    }

    Map<String, ActiveRun> localHandlesRef() {
        return localHandles;
    }

    @PreDestroy
    public void shutdown() {
        renewExecutor.shutdownNow();
    }

    private void renewLocalRuns() {
        for (ActiveRun run : localHandles.values()) {
            try {
                boolean renewed = admissionController.renew(
                        run.runId(), run.namespace(), run.sessionId(), run.userId());
                if (!renewed) {
                    log.warn("Lost run lease, aborting local run: namespace={}, sessionId={}, runId={}",
                            run.namespace(), run.sessionId(), run.runId());
                    run.abort("run_lease_lost");
                }
            } catch (Exception e) {
                log.warn("Failed to renew run lease: namespace={}, sessionId={}, runId={}",
                        run.namespace(), run.sessionId(), run.runId(), e);
            }
        }
    }

    private int activeRunCount(String key) {
        long nowMs = System.currentTimeMillis();
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, nowMs);
        Long count = redisTemplate.opsForZSet().count(key, nowMs, Double.POSITIVE_INFINITY);
        return count == null ? 0 : count.intValue();
    }
}
