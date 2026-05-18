package com.glmapper.coding.core.runtime.subagent;

import com.glmapper.agent.core.Agent;
import com.glmapper.coding.core.cluster.ClusterNodeIdentity;
import com.glmapper.coding.core.mongo.SubagentStateDocument;
import com.glmapper.coding.core.mongo.SubagentStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final Logger log = LoggerFactory.getLogger(SubagentRegistry.class);

    private final Map<String, SubagentState> statesById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> idsByParentRun = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> idsBySession = new ConcurrentHashMap<>();

    private final ObjectProvider<SubagentStateRepository> repositoryProvider;
    private final ObjectProvider<ClusterNodeIdentity> nodeIdentityProvider;

    public SubagentRegistry(ObjectProvider<SubagentStateRepository> repositoryProvider,
                            ObjectProvider<ClusterNodeIdentity> nodeIdentityProvider) {
        this.repositoryProvider = repositoryProvider;
        this.nodeIdentityProvider = nodeIdentityProvider;
    }

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
        persist(context, running);
    }

    public void attachRuntime(String subagentId, Agent agent, CompletableFuture<Void> future) {
        statesById.computeIfPresent(subagentId, (id, state) -> state.withRuntime(agent, future));
    }

    public Optional<SubagentContext> context(String subagentId) {
        SubagentState state = statesById.get(subagentId);
        if (state != null) {
            return Optional.of(state.context());
        }
        return loadFromMongo(subagentId).map(this::contextFromDoc);
    }

    public Optional<SubagentResult> result(String subagentId) {
        SubagentState state = statesById.get(subagentId);
        if (state != null) {
            return Optional.of(state.result());
        }
        return loadFromMongo(subagentId).map(this::resultFromDoc);
    }

    /**
     * Returns the owner node id for a subagent, looking it up from the persistent store if not
     * present locally. Used to route abort commands across nodes.
     */
    public Optional<String> ownerNodeId(String subagentId) {
        if (statesById.containsKey(subagentId)) {
            ClusterNodeIdentity ident = nodeIdentityProvider.getIfAvailable();
            return Optional.ofNullable(ident == null ? null : ident.getNodeId());
        }
        return loadFromMongo(subagentId).map(SubagentStateDocument::getOwnerNodeId);
    }

    public List<SubagentResult> listByParentRun(String parentRunId) {
        Set<String> ids = idsByParentRun.get(parentRunId);
        if (ids != null && !ids.isEmpty()) {
            List<SubagentResult> results = new ArrayList<>();
            for (String id : ids) {
                SubagentState state = statesById.get(id);
                if (state != null) {
                    results.add(state.result());
                }
            }
            return results;
        }
        SubagentStateRepository repo = repositoryProvider.getIfAvailable();
        if (repo == null) {
            return List.of();
        }
        try {
            return repo.findByNamespaceAndParentRunId("*", parentRunId).stream()
                    .map(this::resultFromDoc).toList();
        } catch (Exception e) {
            log.warn("Failed to list subagents from Mongo by parentRunId: {}", parentRunId, e);
            return List.of();
        }
    }

    public int activeCountByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return 0;
        }
        int local = (int) statesById.values().stream()
                .filter(state -> tenantId.equals(state.context().tenantId()))
                .filter(state -> state.result().status() == SubagentStatus.RUNNING)
                .count();
        SubagentStateRepository repo = repositoryProvider.getIfAvailable();
        if (repo == null) {
            return local;
        }
        try {
            // count includes the local ones (already persisted); use Mongo as source of truth
            return (int) repo.countByTenantIdAndStatus(tenantId, SubagentStatus.RUNNING.name());
        } catch (Exception e) {
            log.warn("Falling back to local count: tenant={}", tenantId, e);
            return local;
        }
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
        updateStatus(subagentId, SubagentStatus.COMPLETED, summary, null, details);
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
        updateStatus(subagentId, SubagentStatus.FAILED, null, errorMessage, details);
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
        updateStatus(subagentId, SubagentStatus.ABORTED, null, reason == null ? "aborted" : reason, null);
    }

    private void persist(SubagentContext context, SubagentResult running) {
        SubagentStateRepository repo = repositoryProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        try {
            ClusterNodeIdentity ident = nodeIdentityProvider.getIfAvailable();
            SubagentStateDocument doc = new SubagentStateDocument();
            doc.setId(context.subagentId());
            doc.setParentRunId(context.parentRunId());
            doc.setTenantId(context.tenantId());
            doc.setNamespace(context.namespace());
            doc.setUserId(context.userId());
            doc.setProjectKey(context.projectKey());
            doc.setSessionId(context.sessionId());
            doc.setRole(context.role().name());
            doc.setDepth(context.depth());
            doc.setWorkspaceScope(context.workspaceScope().name());
            doc.setTask(context.task());
            doc.setContextText(context.context());
            doc.setMaxDurationSeconds(context.maxDurationSeconds());
            doc.setOwnerNodeId(ident == null ? null : ident.getNodeId());
            doc.setStatus(running.status().name());
            doc.setStartedAt(running.startedAt());
            doc.setDetails(running.details());
            repo.save(doc);
        } catch (Exception e) {
            log.warn("Failed to persist subagent state: {}", context.subagentId(), e);
        }
    }

    private void updateStatus(String subagentId, SubagentStatus status, String summary, String error, Map<String, Object> details) {
        SubagentStateRepository repo = repositoryProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        try {
            Optional<SubagentStateDocument> existing = repo.findById(subagentId);
            if (existing.isEmpty()) {
                return;
            }
            SubagentStateDocument doc = existing.get();
            doc.setStatus(status.name());
            doc.setSummary(summary);
            doc.setErrorMessage(error);
            doc.setEndedAt(Instant.now());
            if (details != null) {
                doc.setDetails(details);
            }
            repo.save(doc);
        } catch (Exception e) {
            log.warn("Failed to update subagent state: {}", subagentId, e);
        }
    }

    private Optional<SubagentStateDocument> loadFromMongo(String subagentId) {
        SubagentStateRepository repo = repositoryProvider.getIfAvailable();
        if (repo == null) {
            return Optional.empty();
        }
        try {
            return repo.findById(subagentId);
        } catch (Exception e) {
            log.warn("Failed to load subagent from Mongo: {}", subagentId, e);
            return Optional.empty();
        }
    }

    private SubagentContext contextFromDoc(SubagentStateDocument doc) {
        return new SubagentContext(
                doc.getId(),
                doc.getParentRunId(),
                doc.getTenantId(),
                doc.getNamespace(),
                doc.getUserId(),
                doc.getProjectKey(),
                doc.getSessionId(),
                SubagentRole.valueOf(doc.getRole()),
                doc.getDepth(),
                WorkspaceScope.valueOf(doc.getWorkspaceScope()),
                doc.getTask(),
                doc.getContextText(),
                doc.getMaxDurationSeconds(),
                doc.getStartedAt()
        );
    }

    private SubagentResult resultFromDoc(SubagentStateDocument doc) {
        return new SubagentResult(
                doc.getId(),
                doc.getParentRunId(),
                SubagentRole.valueOf(doc.getRole()),
                SubagentStatus.valueOf(doc.getStatus()),
                doc.getSummary(),
                doc.getErrorMessage(),
                doc.getStartedAt(),
                doc.getEndedAt(),
                doc.getDetails() == null ? Map.of() : doc.getDetails()
        );
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
