package com.glmapper.coding.core.runtime;

import com.glmapper.agent.core.AgentAssistantMessage;
import com.glmapper.agent.core.AgentEvent;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.cluster.DistributedLiveRunRegistry;
import com.glmapper.coding.core.execution.WorkspaceStorage;
import com.glmapper.coding.core.runtime.subagent.SubagentRuntime;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentRunRuntime {

    private final AgentSessionRuntime sessionRuntime;
    private final TenantRuntimeGuard tenantRuntimeGuard;
    private final LiveRunRegistry liveRunRegistry;
    private final RunQueueManager runQueueManager;
    private final RuntimeEventPublisher eventPublisher;
    private final RuntimeAuditService runtimeAuditService;
    private final RunFailureClassifier runFailureClassifier;
    private final WorkspaceStorage workspaceStorage;
    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired(required = false)
    private SubagentRuntime subagentRuntime;

    public AgentRunRuntime(AgentSessionRuntime sessionRuntime,
                           TenantRuntimeGuard tenantRuntimeGuard,
                           LiveRunRegistry liveRunRegistry,
                           RunQueueManager runQueueManager,
                           RuntimeEventPublisher eventPublisher,
                           RuntimeAuditService runtimeAuditService,
                           RunFailureClassifier runFailureClassifier,
                           WorkspaceStorage workspaceStorage) {
        this.sessionRuntime = sessionRuntime;
        this.tenantRuntimeGuard = tenantRuntimeGuard;
        this.liveRunRegistry = liveRunRegistry;
        this.runQueueManager = runQueueManager;
        this.eventPublisher = eventPublisher;
        this.runtimeAuditService = runtimeAuditService;
        this.runFailureClassifier = runFailureClassifier;
        this.workspaceStorage = workspaceStorage;
    }

    public void stream(AgentRunRequest request, RuntimeEventSink sink) {
        AgentRunContext context = toContext(request);
        try {
            tenantRuntimeGuard.validateRunContext(context);
            dispatchByQueuePolicy(context, sink);
        } catch (TenantRuntimeGuard.QuotaRejectedException quotaRejected) {
            emitQuotaRejected(context, sink, quotaRejected.getMessage());
        } catch (Exception e) {
            RunFailureType failureType = runFailureClassifier.classify(e);
            eventPublisher.emit(sink, context, "run_failed", Map.of(
                    "runId", context.runId(),
                    "failureType", failureType.name(),
                    "message", e.getMessage() == null ? "runtime error" : e.getMessage()
            ));
            runtimeAuditService.recordRunFailed(context, failureType, e.getMessage());
        }
    }

    public boolean abort(String namespace, String sessionId, String reason) {
        boolean aborted = liveRunRegistry.abortSession(namespace, sessionId, reason == null ? "user_request" : reason);
        if (aborted) {
            sessionRuntime.abort(sessionId, namespace);
        }
        if (subagentRuntime != null) {
            subagentRuntime.abortBySession(namespace, sessionId, reason == null ? "user_request" : reason);
        }
        return aborted;
    }

    public boolean steer(String namespace, String sessionId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean steered = liveRunRegistry.steerSession(namespace, sessionId, text);
        if (!steered) {
            sessionRuntime.steer(sessionId, namespace, text);
        }
        return true;
    }

    /**
     * 基于排队策略调度执行，包含以下几种情况：
     * 1. 立即执行（RUN_NOW）：如果没有活跃的运行，则立即执行新的运行。
     * 2. 中断并执行（INTERRUPT）：如果有活跃的运行，根据策略中断当前运行并立即执行新的运行。
     * 3. 排队等待（ENQUEUE）：如果有活跃的运行，根据策略将新的运行加入队列，等待前一个运行完成后再执行。
     * 4. 引导调整（STEER）：如果有活跃的运行，根据策略引导用户调整输入（如提示用户修改提示词），以满足执行条件。
     * 5. 拒绝执行（REJECT）：如果有活跃的运行，根据策略拒绝新的运行请求，并向用户返回相应的提示信息。
     *
     * @param context
     * @param sink
     */
    private void dispatchByQueuePolicy(AgentRunContext context, RuntimeEventSink sink) {
        Optional<LiveRunRegistry.ActiveRun> active = liveRunRegistry.findBySession(context.namespace(), context.sessionId());
        RunQueueDecision decision = runQueueManager.decide(context, active.isPresent());
        // 记录队列决策，包含是否立即执行、加入队列、拒绝执行等
        runtimeAuditService.recordQueueDecision(context, decision);
        // 根据决策结果调度执行
        switch (decision.type()) {
            // 如果没有活跃运行，或策略允许立即执行，则直接调度执行
            case RUN_NOW -> scheduleRun(context, sink);
            // 如果有活跃运行，且策略为中断并执行，则先中断当前运行，再调度执行
            case INTERRUPT -> {
                tenantRuntimeGuard.ensureQueueCapacity(context, runQueueManager);
                int queueSize = runQueueManager.enqueue(context, sink);
                eventPublisher.emit(sink, context, "queue_updated", Map.of(
                        "runId", context.runId(),
                        "decision", decision.type().name(),
                        "queueSize", queueSize
                ));
                active.ifPresent(r -> r.abort("interrupted_by_new_run"));
            }
            // 如果有活跃运行，且策略为排队等待，则将新的运行加入队列，等待前一个运行完成后再执行
            case ENQUEUE -> {
                tenantRuntimeGuard.ensureQueueCapacity(context, runQueueManager);
                int queueSize = runQueueManager.enqueue(context, sink);
                eventPublisher.emit(sink, context, "queue_updated", Map.of(
                        "runId", context.runId(),
                        "decision", decision.type().name(),
                        "queueSize", queueSize
                ));
            }
            // 如果有活跃运行，且策略为引导调整，则引导用户调整输入（如提示用户修改提示词），以满足执行条件
            case STEER -> {
                boolean steered = steer(context.namespace(), context.sessionId(), context.prompt());
                eventPublisher.emit(sink, context, "queue_updated", Map.of(
                        "runId", context.runId(),
                        "decision", decision.type().name(),
                        "steered", steered
                ));
            }
            // 如果有活跃运行，且策略为拒绝执行，则拒绝新的运行请求，并向用户返回相应的提示信息
            case DROP -> eventPublisher.emit(sink, context, "queue_updated", Map.of(
                    "runId", context.runId(),
                    "decision", decision.type().name(),
                    "dropped", true
            ));
            // 如果有活跃运行，且策略为拒绝执行，则拒绝新的运行请求，并向用户返回相应的提示信息
            case REJECT -> emitQuotaRejected(context, sink, decision.reason());
        }
    }

    /**
     * 调度执行运行，包含以下步骤：
     * 1. 注册活跃运行：将当前运行注册到活跃运行注册表中，以便进行管理和监控。
     * 2. 发布运行开始事件：向事件发布器发布运行开始事件，通知相关系统和组件。
     * 3. 配置运行工具：根据运行上下文配置所需的工具和资源。
     * 4. 订阅运行事件：订阅运行过程中产生的事件，以便进行转发和处理。
     * 5. 执行提示词：执行用户输入的提示词，驱动代理进行交互和操作。
     * 6. 发布运行完成事件：向事件发布器发布运行完成事件，通知相关系统和组件。
     * 7. 错误处理：捕获和处理运行过程中发生的异常，发布运行失败事件，并记录审计日志。
     *
     * @param context
     * @param sink
     */
    private void scheduleRun(AgentRunContext context, RuntimeEventSink sink) {
        tenantRuntimeGuard.ensureCanStartRun(context, liveRunRegistry);
        CompletableFuture.runAsync(() -> executeRun(context, sink, null), runExecutor);
    }

    private void scheduleRun(AgentRunContext context, RuntimeEventSink sink, RunQueueManager.PolledRun polledRun) {
        tenantRuntimeGuard.ensureCanStartRun(context, liveRunRegistry);
        CompletableFuture.runAsync(() -> executeRun(context, sink, polledRun), runExecutor);
    }

    /**
     * 执行运行的具体逻辑，包含以下步骤：
     * 1. 注册活跃运行：将当前运行注册到活跃运行注册表中，以便进行管理和监控。
     * 2. 发布运行开始事件：向事件发布器发布运行开始事件，通知相关系统和组件。
     * 3. 配置运行工具：根据运行上下文配置所需的工具和资源。
     * 4. 订阅运行事件：订阅运行过程中产生的事件，以便进行转发和处理。
     * 5. 执行提示词：执行用户输入的提示词，驱动代理进行交互和操作。
     * 6. 发布运行完成事件：向事件发布器发布运行完成事件，通知相关系统和组件。
     * 7. 错误处理：捕获和处理运行过程中发生的异常，发布运行失败事件，并记录审计日志。
     *
     * @param context
     * @param sink
     */
    private void executeRun(AgentRunContext context, RuntimeEventSink sink, RunQueueManager.PolledRun polledRun) {
        AtomicInteger lastAssistantTextLength = new AtomicInteger(0);
        AutoCloseable subscription = null;
        boolean registered = false;
        boolean polledAcked = false;
        try {
            LiveRunRegistry.ActiveRun activeRun = new LiveRunRegistry.ActiveRun(
                    context.runId(),
                    context.namespace(),
                    context.sessionId(),
                    context.tenantId(),
                    context.userId(),
                    context,
                    sink,
                    reason -> {
                        sessionRuntime.abort(context.sessionId(), context.namespace());
                        if (subagentRuntime != null) {
                            subagentRuntime.abortByParentRun(context.runId(), reason == null ? "run_abort" : reason);
                        }
                    },
                    text -> sessionRuntime.steer(context.sessionId(), context.namespace(), text)
            );
            liveRunRegistry.register(activeRun);
            registered = true;
            if (polledRun != null) {
                runQueueManager.ack(context.namespace(), context.sessionId(), polledRun);
                polledAcked = true;
            }

            workspaceStorage.prepareForRun(context.namespace(), context.sessionId());

            eventPublisher.emit(sink, context, "run_started", Map.of(
                    "runId", context.runId(),
                    "tenantId", context.tenantId(),
                    "sessionId", context.sessionId()
            ));
            // 记录运行开始事件，包含运行ID、租户ID、会话ID等信息
            runtimeAuditService.recordRunStarted(context);
            // 根据运行上下文配置所需的工具和资源，如连接外部系统、加载知识库等
            sessionRuntime.configureRunTools(context);

            subscription = sessionRuntime.subscribeEvents(context.sessionId(), context.namespace(),
                    event -> forwardAgentEvent(context, sink, event, lastAssistantTextLength));

            sessionRuntime.prompt(context.sessionId(), context.namespace(), context.prompt()).join();

            String finalText = sessionRuntime.lastAssistantText(context.sessionId(), context.namespace());
            eventPublisher.emit(sink, context, "run_completed", Map.of(
                    "runId", context.runId(),
                    "status", AgentRunStatus.COMPLETED.name(),
                    "finalText", finalText == null ? "" : finalText
            ));
            runtimeAuditService.recordRunCompleted(context, finalText);
        } catch (CompletionException completionException) {
            Throwable root = completionException.getCause() == null ? completionException : completionException.getCause();
            handleRunError(context, sink, root);
        } catch (DistributedLiveRunRegistry.AdmissionRejectedException admissionRejectedException) {
            if (polledRun != null && !polledAcked && isSessionBusy(admissionRejectedException)) {
                runQueueManager.requeue(context.namespace(), context.sessionId(), polledRun);
                return;
            }
            if (polledRun != null && !polledAcked) {
                runQueueManager.ack(context.namespace(), context.sessionId(), polledRun);
            }
            handleRunError(context, sink, admissionRejectedException);
        } catch (Exception e) {
            handleRunError(context, sink, e);
        } finally {
            closeQuietly(subscription);
            if (registered) {
                liveRunRegistry.complete(context.runId());
                persistWorkspaceAsync(context);
                scheduleNextQueuedRun(context.namespace(), context.sessionId());
            }
        }
    }

    private void persistWorkspaceAsync(AgentRunContext context) {
        try {
            workspaceStorage.persistAfterRun(context.namespace(), context.sessionId())
                    .exceptionally(error -> {
                        // persist failure should not affect run completion; just log
                        return null;
                    });
        } catch (Exception ignored) {
            // never propagate persist errors back to run completion
        }
    }

    private void scheduleNextQueuedRun(String namespace, String sessionId) {
        runQueueManager.pollNext(namespace, sessionId).ifPresent(polled -> {
            try {
                scheduleRun(polled.context(), polled.sink(), polled);
            } catch (TenantRuntimeGuard.QuotaRejectedException quotaRejectedException) {
                runQueueManager.ack(namespace, sessionId, polled);
                emitQuotaRejected(polled.context(), polled.sink(), quotaRejectedException.getMessage());
            } catch (Exception other) {
                // unexpected failure during scheduling — requeue so we don't lose the run
                runQueueManager.requeue(namespace, sessionId, polled);
                throw other;
            }
        });
    }

    private boolean isSessionBusy(Throwable error) {
        return error != null && error.getMessage() != null
                && error.getMessage().contains("session already has active run");
    }

    private void handleRunError(AgentRunContext context, RuntimeEventSink sink, Throwable error) {
        RunFailureType failureType = runFailureClassifier.classify(error);
        eventPublisher.emit(sink, context, "run_failed", Map.of(
                "runId", context.runId(),
                "status", AgentRunStatus.FAILED.name(),
                "failureType", failureType.name(),
                "message", error == null || error.getMessage() == null ? "runtime error" : error.getMessage()
        ));
        runtimeAuditService.recordRunFailed(
                context,
                failureType,
                error == null ? "runtime error" : error.getMessage()
        );
    }

    private void forwardAgentEvent(AgentRunContext context,
                                   RuntimeEventSink sink,
                                   AgentEvent event,
                                   AtomicInteger lastAssistantTextLength) {
        if (event instanceof AgentEvent.MessageStart messageStart) {
            if ("assistant".equalsIgnoreCase(messageStart.message().role())) {
                lastAssistantTextLength.set(0);
            }
            eventPublisher.emit(sink, context, "message_start", Map.of(
                    "role", messageStart.message().role()
            ));
            return;
        }
        if (event instanceof AgentEvent.MessageUpdate messageUpdate) {
            if (messageUpdate.message() instanceof AgentAssistantMessage assistantMessage) {
                String fullText = extractText(assistantMessage.content());
                int previous = lastAssistantTextLength.get();
                if (fullText.length() > previous) {
                    String delta = fullText.substring(previous);
                    lastAssistantTextLength.set(fullText.length());
                    eventPublisher.emit(sink, context, "message_delta", Map.of("delta", delta));
                }
            }
            return;
        }
        if (event instanceof AgentEvent.MessageEnd messageEnd) {
            eventPublisher.emit(sink, context, "message_end", Map.of(
                    "role", messageEnd.message().role()
            ));
            return;
        }
        if (event instanceof AgentEvent.ToolExecutionStart toolStart) {
            eventPublisher.emit(sink, context, "tool_started", Map.of(
                    "toolName", toolStart.toolName(),
                    "toolCallId", toolStart.toolCallId()
            ));
            return;
        }
        if (event instanceof AgentEvent.ToolExecutionUpdate toolUpdate) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", toolUpdate.toolName());
            payload.put("toolCallId", toolUpdate.toolCallId());
            payload.put("partial", toolUpdate.partialResult());
            eventPublisher.emit(sink, context, "tool_updated", payload);
            return;
        }
        if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
            eventPublisher.emit(sink, context, "tool_completed", Map.of(
                    "toolName", toolEnd.toolName(),
                    "toolCallId", toolEnd.toolCallId(),
                    "result", extractText(toolEnd.result().content()),
                    "isError", toolEnd.isError()
            ));
        }
    }

    private void emitQuotaRejected(AgentRunContext context, RuntimeEventSink sink, String reason) {
        String message = reason == null || reason.isBlank() ? "quota rejected" : reason;
        eventPublisher.emit(sink, context, "quota_rejected", Map.of(
                "runId", context.runId(),
                "reason", message
        ));
        runtimeAuditService.recordRunFailed(context, RunFailureType.QUOTA_REJECTED, message);
    }

    private AgentRunContext toContext(AgentRunRequest request) {
        String namespace = request.namespace();
        String tenantId = request.tenantId() == null || request.tenantId().isBlank()
                ? namespace
                : request.tenantId();
        RunQueueMode queueMode = request.queueMode() == null ? RunQueueMode.INTERRUPT : request.queueMode();
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : request.metadata();
        String runId = request.runId() == null || request.runId().isBlank()
                ? "run_" + UUID.randomUUID().toString().replace("-", "")
                : request.runId();

        return new AgentRunContext(
                runId,
                tenantId,
                namespace,
                request.userId(),
                request.projectKey(),
                request.sessionId(),
                request.prompt(),
                request.provider(),
                request.modelId(),
                request.systemPrompt(),
                queueMode,
                Instant.now(),
                metadata
        );
    }

    private String extractText(java.util.List<ContentBlock> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextContent text && text.text() != null) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    private void closeQuietly(AutoCloseable subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception ignored) {
        }
    }

    @PreDestroy
    public void shutdown() {
        runExecutor.shutdown();
    }
}
