package com.glmapper.coding.core.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class LiveRunRegistry {

    private final Map<String, ActiveRun> activeBySession = new ConcurrentHashMap<>();
    private final Map<String, ActiveRun> activeByRunId = new ConcurrentHashMap<>();

    public Optional<ActiveRun> findBySession(String namespace, String sessionId) {
        return Optional.ofNullable(activeBySession.get(sessionKey(namespace, sessionId)));
    }

    public Optional<ActiveRun> findByRunId(String runId) {
        return Optional.ofNullable(activeByRunId.get(runId));
    }

    public int activeCountByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return 0;
        }
        return (int) activeByRunId.values().stream()
                .filter(run -> tenantId.equals(run.tenantId()))
                .count();
    }

    public int activeCountByUser(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return 0;
        }
        return (int) activeByRunId.values().stream()
                .filter(run -> tenantId.equals(run.tenantId()) && userId.equals(run.userId()))
                .count();
    }

    public void register(ActiveRun run) {
        String key = sessionKey(run.namespace(), run.sessionId());
        activeBySession.put(key, run);
        activeByRunId.put(run.runId(), run);
    }

    public void complete(String runId) {
        ActiveRun run = activeByRunId.remove(runId);
        if (run != null) {
            activeBySession.remove(sessionKey(run.namespace(), run.sessionId()), run);
        }
    }

    public boolean abortSession(String namespace, String sessionId, String reason) {
        ActiveRun run = activeBySession.get(sessionKey(namespace, sessionId));
        if (run == null) {
            return false;
        }
        run.abort(reason);
        return true;
    }

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

    public record ActiveRun(String runId,
                            String namespace,
                            String sessionId,
                            String tenantId,
                            String userId,
                            AgentRunContext context,
                            RuntimeEventSink sink,
                            RunAbortController abortController,
                            Consumer<String> steerHandler) {

        public void abort(String reason) {
            if (abortController != null) {
                abortController.abort(reason);
            }
        }

        public void steer(String text) {
            if (steerHandler != null && text != null && !text.isBlank()) {
                steerHandler.accept(text);
            }
        }
    }
}
