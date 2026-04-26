package com.glmapper.coding.core.runtime;

import com.glmapper.coding.core.mongo.SessionDocument;
import com.glmapper.coding.core.mongo.SessionRepository;
import com.glmapper.coding.core.tenant.TenantQuota;
import com.glmapper.coding.core.tenant.TenantQuotaManager;
import org.springframework.stereotype.Component;

@Component
public class TenantRuntimeGuard {

    private final SessionRepository sessionRepository;
    private final TenantQuotaManager tenantQuotaManager;

    private static final int DEFAULT_MAX_QUEUED_RUNS_PER_SESSION = 20;

    public TenantRuntimeGuard(SessionRepository sessionRepository,
                              TenantQuotaManager tenantQuotaManager) {
        this.sessionRepository = sessionRepository;
        this.tenantQuotaManager = tenantQuotaManager;
    }

    public SessionDocument requireSession(String namespace, String sessionId) {
        return sessionRepository.findByIdAndNamespace(sessionId, namespace)
                .orElseThrow(() -> new IllegalArgumentException("Session not found in namespace: " + sessionId));
    }

    public void validateRunContext(AgentRunContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Run context cannot be null");
        }
        if (isBlank(context.namespace())) {
            throw new IllegalArgumentException("namespace cannot be blank");
        }
        if (isBlank(context.sessionId())) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        if (isBlank(context.prompt())) {
            throw new IllegalArgumentException("prompt cannot be blank");
        }
        if (context.tenantId() != null && !context.tenantId().isBlank()
                && !context.tenantId().equals(context.namespace())) {
            throw new IllegalArgumentException("tenantId and namespace must match");
        }
        requireSession(context.namespace(), context.sessionId());
    }

    public void ensureCanStartRun(AgentRunContext context, LiveRunRegistry liveRunRegistry) {
        TenantQuota quota = tenantQuotaManager.resolve(context.namespace());
        int tenantActive = liveRunRegistry.activeCountByTenant(context.namespace());
        if (tenantActive >= quota.maxConcurrentSessions()) {
            throw new QuotaRejectedException("tenant concurrent run quota exceeded");
        }

        if (context.userId() != null && !context.userId().isBlank()) {
            int userActive = liveRunRegistry.activeCountByUser(context.namespace(), context.userId());
            int userSoftLimit = Math.max(1, quota.maxConcurrentSessions() / 2);
            if (userActive >= userSoftLimit) {
                throw new QuotaRejectedException("user concurrent run quota exceeded");
            }
        }
    }

    public void ensureQueueCapacity(AgentRunContext context, RunQueueManager queueManager) {
        int queued = queueManager.queueSize(context.namespace(), context.sessionId());
        if (queued >= DEFAULT_MAX_QUEUED_RUNS_PER_SESSION) {
            throw new QuotaRejectedException("session queue is full");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class QuotaRejectedException extends RuntimeException {
        public QuotaRejectedException(String message) {
            super(message);
        }
    }
}
