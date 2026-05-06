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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class SubagentRuntime {

    /**
     * liveRunRegistry 当前活跃的Agent运行实例，获取父运行上下文和事件Sink，以便子Agent能够正确关联和发送事件。
     */
    private final LiveRunRegistry liveRunRegistry;
    /**
     * subagentRegistry 管理所有子Agent的上下文和状态，提供注册、查询、更新和中止功能，确保子Agent的生命周期得到正确管理。
     */
    private final SubagentRegistry subagentRegistry;
    /**
     * subagentQuotaGuard 在创建子Agent之前进行配额检查，确保租户和用户不会超出预设的子Agent数量限制，保护系统资源。
     */
    private final SubagentQuotaGuard subagentQuotaGuard;
    /**
     * subagentRunner 负责实际启动和监控子Agent的执行，提供异步结果处理和回调机制，以便在子Agent完成时能够正确更新状态和发送事件。
     */
    private final SubagentRunner subagentRunner;
    /**
     * runtimeEventPublisher 用于发布子Agent相关的事件，如启动、完成和失败等，确保这些事件能够被系统其他部分正确接收和处理。
     */
    private final RuntimeEventPublisher runtimeEventPublisher;
    /**
     * runtimeAuditService 负责记录子Agent的关键操作和状态变化，提供审计日志以便后续查询和分析，确保系统的可追溯性和安全性。
     */
    private final RuntimeAuditService runtimeAuditService;
    /**
     * watcherExecutor 用于异步处理子Agent完成后的回调，确保这些回调不会阻塞主线程，并且能够高效地处理大量子Agent的完成事件。
     */
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

    /**
     * 创建并启动一个新的子Agent，关联到当前会话的父运行上下文，并根据提供的参数配置子Agent的角色、任务和工作空间范围等属性。
     * 在启动子Agent之前进行配额检查，确保不会超出限制，并在子Agent完成后通过回调机制更新状态和发送事件。
     *
     * @param namespace          当前会话的命名空间，用于关联父运行上下文。
     * @param sessionId          当前会话的ID，用于关联父运行上下文。
     * @param role               子Agent的角色，如Coder、Tester等，决定子Agent的行为和权限。
     * @param task               子Agent需要执行的具体任务描述，不能为空或空白。
     * @param contextText        额外的上下文信息，可以提供给子Agent以辅助其执行任务。
     * @param workspaceScope     子Agent的工作空间范围，如SESSION、RUN等，决定子Agent的数据隔离级别。
     * @param asyncMode          是否以异步模式启动子Agent，如果为true则立即返回结果，否则等待子Agent完成后返回结果。
     * @param maxDurationSeconds 子Agent的最大执行时长，单位为秒，确保子Agent不会无限期运行。
     * @return 如果asyncMode为true，则返回一个包含子Agent基本信息和状态的SubagentResult对象；如果asyncMode为false，则等待子Agent完成后返回完整的SubagentResult对象。
     */
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
