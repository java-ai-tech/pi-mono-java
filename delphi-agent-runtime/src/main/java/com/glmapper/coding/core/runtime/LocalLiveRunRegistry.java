package com.glmapper.coding.core.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalLiveRunRegistry implements LiveRunRegistry {

    private final Map<String, ActiveRun> activeBySession = new ConcurrentHashMap<>();
    private final Map<String, ActiveRun> activeByRunId = new ConcurrentHashMap<>();

    @Override
    public Optional<ActiveRun> findBySession(String namespace, String sessionId) {
        return Optional.ofNullable(activeBySession.get(sessionKey(namespace, sessionId)));
    }

    @Override
    public Optional<ActiveRun> findByRunId(String runId) {
        return Optional.ofNullable(activeByRunId.get(runId));
    }

    @Override
    public int activeCountByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return 0;
        }
        return (int) activeByRunId.values().stream()
                .filter(run -> tenantId.equals(run.tenantId()))
                .count();
    }

    @Override
    public int activeCountByUser(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return 0;
        }
        return (int) activeByRunId.values().stream()
                .filter(run -> tenantId.equals(run.tenantId()) && userId.equals(run.userId()))
                .count();
    }

    @Override
    public void register(ActiveRun run) {
        String key = sessionKey(run.namespace(), run.sessionId());
        activeBySession.put(key, run);
        activeByRunId.put(run.runId(), run);
    }

    @Override
    public void complete(String runId) {
        ActiveRun run = activeByRunId.remove(runId);
        if (run != null) {
            activeBySession.remove(sessionKey(run.namespace(), run.sessionId()), run);
        }
    }

    @Override
    public boolean abortSession(String namespace, String sessionId, String reason) {
        ActiveRun run = activeBySession.get(sessionKey(namespace, sessionId));
        if (run == null) {
            return false;
        }
        run.abort(reason);
        return true;
    }

    @Override
    public boolean steerSession(String namespace, String sessionId, String text) {
        ActiveRun run = activeBySession.get(sessionKey(namespace, sessionId));
        if (run == null) {
            return false;
        }
        run.steer(text);
        return true;
    }

    private String sessionKey(String namespace, String sessionId) {
        return namespace + ":" + sessionId;
    }
}
