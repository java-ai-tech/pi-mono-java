package com.glmapper.coding.core.runtime;

import com.glmapper.agent.core.AgentAssistantMessage;
import com.glmapper.agent.core.AgentEvent;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.TextContent;
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
    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired(required = false)
    private SubagentRuntime subagentRuntime;

    public AgentRunRuntime(AgentSessionRuntime sessionRuntime,
                           TenantRuntimeGuard tenantRuntimeGuard,
                           LiveRunRegistry liveRunRegistry,
                           RunQueueManager runQueueManager,
                           RuntimeEventPublisher eventPublisher,
                           RuntimeAuditService runtimeAuditService,
                           RunFailureClassifier runFailureClassifier) {
        this.sessionRuntime = sessionRuntime;
        this.tenantRuntimeGuard = tenantRuntimeGuard;
        this.liveRunRegistry = liveRunRegistry;
        this.runQueueManager = runQueueManager;
        this.eventPublisher = eventPublisher;
        this.runtimeAuditService = runtimeAuditService;
        this.runFailureClassifier = runFailureClassifier;
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

    private void dispatchByQueuePolicy(AgentRunContext context, RuntimeEventSink sink) {
        Optional<LiveRunRegistry.ActiveRun> active = liveRunRegistry.findBySession(context.namespace(), context.sessionId());
        RunQueueDecision decision = runQueueManager.decide(context, active.isPresent());
        runtimeAuditService.recordQueueDecision(context, decision);

        switch (decision.type()) {
            case RUN_NOW -> scheduleRun(context, sink);
            case INTERRUPT -> {
                active.ifPresent(r -> r.abort("interrupted_by_new_run"));
                scheduleRun(context, sink);
            }
            case ENQUEUE -> {
                tenantRuntimeGuard.ensureQueueCapacity(context, runQueueManager);
                int queueSize = runQueueManager.enqueue(context, sink);
                eventPublisher.emit(sink, context, "queue_updated", Map.of(
                        "runId", context.runId(),
                        "decision", decision.type().name(),
                        "queueSize", queueSize
                ));
            }
            case STEER -> {
                boolean steered = steer(context.namespace(), context.sessionId(), context.prompt());
                eventPublisher.emit(sink, context, "queue_updated", Map.of(
                        "runId", context.runId(),
                        "decision", decision.type().name(),
                        "steered", steered
                ));
            }
            case DROP -> eventPublisher.emit(sink, context, "queue_updated", Map.of(
                    "runId", context.runId(),
                    "decision", decision.type().name(),
                    "dropped", true
            ));
            case REJECT -> emitQuotaRejected(context, sink, decision.reason());
        }
    }

    private void scheduleRun(AgentRunContext context, RuntimeEventSink sink) {
        tenantRuntimeGuard.ensureCanStartRun(context, liveRunRegistry);
        CompletableFuture.runAsync(() -> executeRun(context, sink), runExecutor);
    }

    private void executeRun(AgentRunContext context, RuntimeEventSink sink) {
        AtomicInteger lastAssistantTextLength = new AtomicInteger(0);
        AutoCloseable subscription = null;
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

            eventPublisher.emit(sink, context, "run_started", Map.of(
                    "runId", context.runId(),
                    "tenantId", context.tenantId(),
                    "sessionId", context.sessionId()
            ));
            runtimeAuditService.recordRunStarted(context);
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
        } catch (Exception e) {
            handleRunError(context, sink, e);
        } finally {
            closeQuietly(subscription);
            liveRunRegistry.complete(context.runId());
            scheduleNextQueuedRun(context.namespace(), context.sessionId());
        }
    }

    private void scheduleNextQueuedRun(String namespace, String sessionId) {
        runQueueManager.pollNext(namespace, sessionId).ifPresent(queued -> {
            try {
                scheduleRun(queued.context(), queued.sink());
            } catch (TenantRuntimeGuard.QuotaRejectedException quotaRejectedException) {
                emitQuotaRejected(queued.context(), queued.sink(), quotaRejectedException.getMessage());
            }
        });
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

        return new AgentRunContext(
                "run_" + UUID.randomUUID().toString().replace("-", ""),
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
