package com.glmapper.coding.core.runtime.subagent;

import com.glmapper.agent.core.Agent;
import com.glmapper.agent.core.AgentAssistantMessage;
import com.glmapper.agent.core.AgentEvent;
import com.glmapper.agent.core.AgentMessage;
import com.glmapper.agent.core.AgentOptions;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.TextContent;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.core.runtime.AgentRunContext;
import com.glmapper.coding.core.runtime.RuntimeEventPublisher;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import com.glmapper.coding.core.tools.policy.ToolInventory;
import com.glmapper.coding.core.tools.policy.ToolPolicyPipeline;
import com.glmapper.coding.core.tools.policy.ToolRuntimeContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SubagentRunner {

    private final AiRuntime aiRuntime;
    private final ModelCatalog modelCatalog;
    private final ToolInventory toolInventory;
    private final ToolPolicyPipeline toolPolicyPipeline;
    private final RuntimeEventPublisher runtimeEventPublisher;

    public SubagentRunner(AiRuntime aiRuntime,
                          ModelCatalog modelCatalog,
                          ToolInventory toolInventory,
                          ToolPolicyPipeline toolPolicyPipeline,
                          RuntimeEventPublisher runtimeEventPublisher) {
        this.aiRuntime = aiRuntime;
        this.modelCatalog = modelCatalog;
        this.toolInventory = toolInventory;
        this.toolPolicyPipeline = toolPolicyPipeline;
        this.runtimeEventPublisher = runtimeEventPublisher;
    }

    public ExecutionHandle start(SubagentContext subagentContext,
                                 AgentRunContext parentContext,
                                 RuntimeEventSink sink) {
        Model model = resolveModel(parentContext);
        Agent agent = new Agent(aiRuntime, model, AgentOptions.defaults());

        ToolRuntimeContext toolRuntimeContext = new ToolRuntimeContext(
                subagentContext.tenantId(),
                subagentContext.namespace(),
                subagentContext.userId(),
                subagentContext.projectKey(),
                subagentContext.sessionId(),
                subagentContext.parentRunId(),
                subagentContext.subagentId(),
                subagentContext.role(),
                subagentContext.depth(),
                subagentContext.workspaceScope()
        );
        agent.state().tools(toolPolicyPipeline.apply(toolRuntimeContext, toolInventory.collect(toolRuntimeContext)));
        agent.state().systemPrompt(buildSystemPrompt(subagentContext));

        AtomicInteger assistantTextLength = new AtomicInteger(0);
        AutoCloseable subscription = agent.subscribe(event -> forwardEvent(
                event,
                parentContext,
                sink,
                subagentContext.subagentId(),
                assistantTextLength
        ));

        String prompt = composePrompt(subagentContext);
        CompletableFuture<Void> promptFuture = agent.prompt(prompt);
        CompletableFuture<Void> timedPromptFuture = promptFuture
                .orTimeout(subagentContext.maxDurationSeconds(), TimeUnit.SECONDS)
                .whenComplete((ignored, throwable) -> {
                    if (unwrap(throwable) instanceof TimeoutException) {
                        agent.abort();
                        promptFuture.cancel(true);
                    }
                });
        CompletableFuture<SubagentResult> resultFuture = timedPromptFuture.handle((ignored, throwable) -> {
            closeQuietly(subscription);
            if (throwable != null) {
                Throwable root = unwrap(throwable);
                SubagentStatus status = root instanceof TimeoutException ? SubagentStatus.FAILED : SubagentStatus.FAILED;
                return new SubagentResult(
                        subagentContext.subagentId(),
                        subagentContext.parentRunId(),
                        subagentContext.role(),
                        status,
                        null,
                        root.getMessage(),
                        subagentContext.startedAt(),
                        Instant.now(),
                        Map.of()
                );
            }
            String summary = extractLastAssistantText(agent.state().messages());
            return new SubagentResult(
                    subagentContext.subagentId(),
                    subagentContext.parentRunId(),
                    subagentContext.role(),
                    SubagentStatus.COMPLETED,
                    summary,
                    null,
                    subagentContext.startedAt(),
                    Instant.now(),
                    Map.of("messageCount", agent.state().messages().size())
            );
        });
        return new ExecutionHandle(agent, promptFuture, resultFuture);
    }

    private void forwardEvent(AgentEvent event,
                              AgentRunContext parentContext,
                              RuntimeEventSink sink,
                              String subagentId,
                              AtomicInteger assistantTextLength) {
        if (event instanceof AgentEvent.MessageUpdate messageUpdate) {
            if (messageUpdate.message() instanceof AgentAssistantMessage assistant) {
                String fullText = extractText(assistant.content());
                int previous = assistantTextLength.get();
                if (fullText.length() > previous) {
                    String delta = fullText.substring(previous);
                    assistantTextLength.set(fullText.length());
                    runtimeEventPublisher.emitSubagent(
                            sink,
                            parentContext,
                            subagentId,
                            "subagent_message_delta",
                            Map.of("delta", delta)
                    );
                }
            }
            return;
        }
        if (event instanceof AgentEvent.ToolExecutionStart toolExecutionStart) {
            runtimeEventPublisher.emitSubagent(
                    sink,
                    parentContext,
                    subagentId,
                    "subagent_tool_started",
                    Map.of(
                            "toolName", toolExecutionStart.toolName(),
                            "toolCallId", toolExecutionStart.toolCallId()
                    )
            );
            return;
        }
        if (event instanceof AgentEvent.ToolExecutionEnd toolExecutionEnd) {
            runtimeEventPublisher.emitSubagent(
                    sink,
                    parentContext,
                    subagentId,
                    "subagent_tool_completed",
                    Map.of(
                            "toolName", toolExecutionEnd.toolName(),
                            "toolCallId", toolExecutionEnd.toolCallId(),
                            "isError", toolExecutionEnd.isError()
                    )
            );
        }
    }

    private Model resolveModel(AgentRunContext parentContext) {
        if (parentContext.provider() != null && parentContext.modelId() != null) {
            return modelCatalog.get(parentContext.provider(), parentContext.modelId())
                    .orElseGet(() -> firstModel());
        }
        return firstModel();
    }

    private Model firstModel() {
        List<Model> models = modelCatalog.getAll().stream().toList();
        if (models.isEmpty()) {
            throw new IllegalStateException("No available models");
        }
        return models.get(0);
    }

    private String buildSystemPrompt(SubagentContext context) {
        String rolePrompt = switch (context.role()) {
            case PLANNER -> "You are a planner subagent. Focus on decomposition and step ordering.";
            case RESEARCHER -> "You are a researcher subagent. Focus on collecting and summarizing facts from workspace context.";
            case REVIEWER -> "You are a reviewer subagent. Focus on risks, regressions, and correctness.";
            case CODER -> "You are a coder subagent. Implement changes precisely and verify with commands when needed.";
            case TESTER -> "You are a tester subagent. Focus on deterministic test execution and concise failure analysis.";
            case ORCHESTRATOR -> "You are an orchestrator subagent. Delegate only when policy allows.";
        };
        return rolePrompt + "\nKeep all work strictly inside assigned tenant/session workspace boundaries.";
    }

    private String composePrompt(SubagentContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Task:\n").append(context.task());
        if (context.context() != null && !context.context().isBlank()) {
            prompt.append("\n\nContext:\n").append(context.context());
        }
        prompt.append("\n\nReturn concise completion notes and what changed.");
        return prompt.toString();
    }

    private String extractLastAssistantText(List<AgentMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            if (message instanceof AgentAssistantMessage assistantMessage) {
                return extractText(assistantMessage.content());
            }
        }
        return "";
    }

    private String extractText(List<ContentBlock> content) {
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

    private Throwable unwrap(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    public record ExecutionHandle(
            Agent agent,
            CompletableFuture<Void> promptFuture,
            CompletableFuture<SubagentResult> resultFuture
    ) {
    }
}
