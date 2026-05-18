package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import com.glmapper.coding.core.runtime.AgentRunContext;
import com.glmapper.coding.core.runtime.LiveRunRegistry;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import com.glmapper.coding.core.tenant.TenantQuota;
import com.glmapper.coding.core.tenant.TenantQuotaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    public DistributedLiveRunRegistry(RunAdmissionController admissionController,
                                      RunCommandDispatcher commandDispatcher,
                                      StringRedisTemplate redisTemplate,
                                      ClusterKeyRegistry keyRegistry,
                                      TenantQuotaManager tenantQuotaManager) {
        this.admissionController = admissionController;
        this.commandDispatcher = commandDispatcher;
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.tenantQuotaManager = tenantQuotaManager;
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
        return localHandles.values().stream()
                .filter(r -> namespace.equals(r.namespace()) && sessionId.equals(r.sessionId()))
                .findFirst();
    }

    @Override
    public Optional<ActiveRun> findByRunId(String runId) {
        return Optional.ofNullable(localHandles.get(runId));
    }

    @Override
    public int activeCountByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return 0;
        Long size = redisTemplate.opsForSet().size(keyRegistry.tenantActiveCountKey(tenantId));
        return size == null ? 0 : size.intValue();
    }

    @Override
    public int activeCountByUser(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) return 0;
        Long size = redisTemplate.opsForSet().size(keyRegistry.userActiveCountKey(tenantId, userId));
        return size == null ? 0 : size.intValue();
    }

    @Override
    public boolean abortSession(String namespace, String sessionId, String reason) {
        Optional<ActiveRun> local = findBySession(namespace, sessionId);
        if (local.isPresent()) {
            local.get().abort(reason);
            return true;
        }
        return commandDispatcher.sendAbort(namespace, sessionId, reason);
    }

    @Override
    public boolean steerSession(String namespace, String sessionId, String text) {
        Optional<ActiveRun> local = findBySession(namespace, sessionId);
        if (local.isPresent()) {
            local.get().steer(text);
            return true;
        }
        return commandDispatcher.sendSteer(namespace, sessionId, text);
    }

    public static class AdmissionRejectedException extends RuntimeException {
        public AdmissionRejectedException(String message) {
            super(message);
        }
    }

    Map<String, ActiveRun> localHandlesRef() {
        return localHandles;
    }
}
