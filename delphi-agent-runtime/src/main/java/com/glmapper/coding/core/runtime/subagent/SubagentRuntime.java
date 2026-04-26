package com.glmapper.coding.core.runtime.subagent;

import com.glmapper.coding.core.runtime.AgentRunContext;
import com.glmapper.coding.core.runtime.LiveRunRegistry;
import com.glmapper.coding.core.runtime.RuntimeAuditService;
import com.glmapper.coding.core.runtime.RuntimeEventPublisher;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class SubagentRuntime {

    private final LiveRunRegistry liveRunRegistry;
    private final SubagentRegistry subagentRegistry;
    private final SubagentQuotaGuard subagentQuotaGuard;
    private final SubagentRunner subagentRunner;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final RuntimeAuditService runtimeAuditService;
    private final ExecutorService watcherExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SubagentRuntime(LiveRunRegistry liveRunRegistry,
                           SubagentRegistry subagentRegistry,
                           SubagentQuotaGuard subagentQuotaGuard,
                           SubagentRunner subagentRunner,
                           RuntimeEventPublisher runtimeEventPublisher,
                           RuntimeAuditService runtimeAuditService) {
        this.liveRunRegistry = liveRunRegistry;
        this.subagentRegistry = subagentRegistry;
        this.subagentQuotaGuard = subagentQuotaGuard;
        this.subagentRunner = subagentRunner;
        this.runtimeEventPublisher = runtimeEventPublisher;
        this.runtimeAuditService = runtimeAuditService;
    }

    public SubagentResult spawn(String namespace,
                                String sessionId,
                                SubagentRole role,
                                String task,
                                String contextText,
                                WorkspaceScope workspaceScope,
                                boolean asyncMode,
                                int maxDurationSeconds) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("subagent task cannot be blank");
        }
        LiveRunRegistry.ActiveRun activeRun = liveRunRegistry.findBySession(namespace, sessionId)
                .orElseThrow(() -> new IllegalStateException("No active parent run in current session"));

        AgentRunContext parentContext = activeRun.context();
        RuntimeEventSink sink = activeRun.sink();
        SubagentContext subagentContext = new SubagentContext(
                "sub_" + UUID.randomUUID().toString().replace("-", ""),
                parentContext.runId(),
                parentContext.tenantId(),
                parentContext.namespace(),
                parentContext.userId(),
                parentContext.projectKey(),
                parentContext.sessionId(),
                role == null ? SubagentRole.CODER : role,
                1,
                workspaceScope == null ? WorkspaceScope.SESSION : workspaceScope,
                task,
                contextText,
                Math.max(30, maxDurationSeconds),
                Instant.now()
        );
        subagentQuotaGuard.ensureAllowed(subagentContext);
        subagentRegistry.register(subagentContext);

        runtimeEventPublisher.emitSubagent(
                sink,
                parentContext,
                subagentContext.subagentId(),
                "subagent_started",
                Map.of(
                        "subagentId", subagentContext.subagentId(),
                        "role", subagentContext.role().name(),
                        "workspaceScope", subagentContext.workspaceScope().name(),
                        "mode", asyncMode ? "async" : "sync"
                )
        );

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("subagentId", subagentContext.subagentId());
        auditDetails.put("role", subagentContext.role().name());
        auditDetails.put("workspaceScope", subagentContext.workspaceScope().name());
        runtimeAuditService.record(parentContext, "subagent_spawned", auditDetails);

        SubagentRunner.ExecutionHandle handle = subagentRunner.start(subagentContext, parentContext, sink);
        handle.resultFuture()
                .thenAcceptAsync(result -> onSubagentFinished(parentContext, sink, result), watcherExecutor);
        subagentRegistry.attachRuntime(subagentContext.subagentId(), handle.agent(), handle.promptFuture());

        if (asyncMode) {
            return new SubagentResult(
                    subagentContext.subagentId(),
                    subagentContext.parentRunId(),
                    subagentContext.role(),
                    SubagentStatus.RUNNING,
                    null,
                    null,
                    subagentContext.startedAt(),
                    null,
                    Map.of("resultAvailable", false)
            );
        }
        return handle.resultFuture().join();
    }

    public Optional<SubagentResult> result(String subagentId) {
        return subagentRegistry.result(subagentId);
    }

    public Optional<SubagentStatus> status(String subagentId) {
        return result(subagentId).map(SubagentResult::status);
    }

    public boolean abort(String subagentId, String reason) {
        Optional<SubagentContext> context = subagentRegistry.context(subagentId);
        boolean aborted = subagentRegistry.abort(subagentId, reason);
        if (aborted && context.isPresent()) {
            LiveRunRegistry.ActiveRun activeRun = liveRunRegistry.findByRunId(context.get().parentRunId()).orElse(null);
            if (activeRun != null) {
                runtimeEventPublisher.emitSubagent(
                        activeRun.sink(),
                        activeRun.context(),
                        subagentId,
                        "subagent_failed",
                        Map.of("reason", reason == null ? "aborted" : reason, "status", SubagentStatus.ABORTED.name())
                );
            }
        }
        return aborted;
    }

    public void abortByParentRun(String parentRunId, String reason) {
        subagentRegistry.abortByParentRun(parentRunId, reason);
    }

    public void abortBySession(String namespace, String sessionId, String reason) {
        subagentRegistry.abortBySession(namespace, sessionId, reason);
    }

    private void onSubagentFinished(AgentRunContext parentContext, RuntimeEventSink sink, SubagentResult result) {
        if (result.status() == SubagentStatus.COMPLETED) {
            subagentRegistry.markCompleted(
                    result.subagentId(),
                    result.summary(),
                    result.details()
            );
            runtimeEventPublisher.emitSubagent(
                    sink,
                    parentContext,
                    result.subagentId(),
                    "subagent_completed",
                    Map.of(
                            "status", result.status().name(),
                            "summary", result.summary() == null ? "" : result.summary()
                    )
            );
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("subagentId", result.subagentId());
            details.put("status", result.status().name());
            details.put("summaryLength", result.summary() == null ? 0 : result.summary().length());
            runtimeAuditService.record(parentContext, "subagent_completed", details);
        } else {
            subagentRegistry.markFailed(
                    result.subagentId(),
                    result.errorMessage(),
                    result.details()
            );
            runtimeEventPublisher.emitSubagent(
                    sink,
                    parentContext,
                    result.subagentId(),
                    "subagent_failed",
                    Map.of(
                            "status", result.status().name(),
                            "error", result.errorMessage() == null ? "" : result.errorMessage()
                    )
            );
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("subagentId", result.subagentId());
            details.put("status", result.status().name());
            details.put("error", result.errorMessage());
            runtimeAuditService.record(parentContext, "subagent_failed", details);
        }
    }

    @PreDestroy
    public void shutdown() {
        watcherExecutor.shutdown();
    }
}
