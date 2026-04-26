package com.glmapper.coding.core.runtime.subagent;

import com.glmapper.agent.core.Agent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SubagentRegistry {

    private final Map<String, SubagentState> statesById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> idsByParentRun = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> idsBySession = new ConcurrentHashMap<>();

    public void register(SubagentContext context) {
        SubagentResult running = new SubagentResult(
                context.subagentId(),
                context.parentRunId(),
                context.role(),
                SubagentStatus.RUNNING,
                null,
                null,
                context.startedAt(),
                null,
                Map.of("workspaceScope", context.workspaceScope().name())
        );
        statesById.put(context.subagentId(), new SubagentState(context, running, null, null));
        idsByParentRun.computeIfAbsent(context.parentRunId(), ignored -> ConcurrentHashMap.newKeySet()).add(context.subagentId());
        idsBySession.computeIfAbsent(sessionKey(context.namespace(), context.sessionId()), ignored -> ConcurrentHashMap.newKeySet())
                .add(context.subagentId());
    }

    public void attachRuntime(String subagentId, Agent agent, CompletableFuture<Void> future) {
        statesById.computeIfPresent(subagentId, (id, state) -> state.withRuntime(agent, future));
    }

    public Optional<SubagentContext> context(String subagentId) {
        SubagentState state = statesById.get(subagentId);
        return state == null ? Optional.empty() : Optional.of(state.context());
    }

    public Optional<SubagentResult> result(String subagentId) {
        SubagentState state = statesById.get(subagentId);
        return state == null ? Optional.empty() : Optional.of(state.result());
    }

    public List<SubagentResult> listByParentRun(String parentRunId) {
        Set<String> ids = idsByParentRun.get(parentRunId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<SubagentResult> results = new ArrayList<>();
        for (String id : ids) {
            SubagentState state = statesById.get(id);
            if (state != null) {
                results.add(state.result());
            }
        }
        return results;
    }

    public int activeCountByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return 0;
        }
        return (int) statesById.values().stream()
                .filter(state -> tenantId.equals(state.context().tenantId()))
                .filter(state -> state.result().status() == SubagentStatus.RUNNING)
                .count();
    }

    public int activeCountByParentRun(String parentRunId) {
        return (int) listByParentRun(parentRunId).stream()
                .filter(result -> result.status() == SubagentStatus.RUNNING)
                .count();
    }

    public void markCompleted(String subagentId, String summary, Map<String, Object> details) {
        statesById.computeIfPresent(subagentId, (id, state) -> state.withResult(
                new SubagentResult(
                        id,
                        state.context().parentRunId(),
                        state.context().role(),
                        SubagentStatus.COMPLETED,
                        summary,
                        null,
                        state.result().startedAt(),
                        Instant.now(),
                        details == null ? Map.of() : details
                )
        ));
    }

    public void markFailed(String subagentId, String errorMessage, Map<String, Object> details) {
        statesById.computeIfPresent(subagentId, (id, state) -> state.withResult(
                new SubagentResult(
                        id,
                        state.context().parentRunId(),
                        state.context().role(),
                        SubagentStatus.FAILED,
                        null,
                        errorMessage,
                        state.result().startedAt(),
                        Instant.now(),
                        details == null ? Map.of() : details
                )
        ));
    }

    public boolean abort(String subagentId, String reason) {
        SubagentState state = statesById.get(subagentId);
        if (state == null) {
            return false;
        }
        if (state.agent() != null) {
            state.agent().abort();
        }
        if (state.future() != null) {
            state.future().cancel(true);
        }
        markAborted(subagentId, reason);
        return true;
    }

    public void abortByParentRun(String parentRunId, String reason) {
        Set<String> ids = idsByParentRun.get(parentRunId);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            abort(id, reason);
        }
    }

    public void abortBySession(String namespace, String sessionId, String reason) {
        Set<String> ids = idsBySession.get(sessionKey(namespace, sessionId));
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            abort(id, reason);
        }
    }

    private void markAborted(String subagentId, String reason) {
        statesById.computeIfPresent(subagentId, (id, state) -> state.withResult(
                new SubagentResult(
                        id,
                        state.context().parentRunId(),
                        state.context().role(),
                        SubagentStatus.ABORTED,
                        null,
                        reason == null ? "aborted" : reason,
                        state.result().startedAt(),
                        Instant.now(),
                        Map.of()
                )
        ));
    }

    private String sessionKey(String namespace, String sessionId) {
        return namespace + ":" + sessionId;
    }

    private record SubagentState(
            SubagentContext context,
            SubagentResult result,
            Agent agent,
            CompletableFuture<Void> future
    ) {
        private SubagentState withRuntime(Agent updatedAgent, CompletableFuture<Void> updatedFuture) {
            return new SubagentState(context, result, updatedAgent, updatedFuture);
        }

        private SubagentState withResult(SubagentResult updatedResult) {
            return new SubagentState(context, updatedResult, agent, future);
        }
    }
}

