package com.glmapper.coding.core.runtime;

import java.util.Optional;

public interface LiveRunRegistry {

    void register(ActiveRun run);

    void complete(String runId);

    Optional<ActiveRun> findBySession(String namespace, String sessionId);

    Optional<ActiveRun> findByRunId(String runId);

    int activeCountByTenant(String tenantId);

    int activeCountByUser(String tenantId, String userId);

    boolean abortSession(String namespace, String sessionId, String reason);

    boolean steerSession(String namespace, String sessionId, String text);

    record ActiveRun(String runId,
                     String namespace,
                     String sessionId,
                     String tenantId,
                     String userId,
                     AgentRunContext context,
                     RuntimeEventSink sink,
                     RunAbortController abortController,
                     java.util.function.Consumer<String> steerHandler) {

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
