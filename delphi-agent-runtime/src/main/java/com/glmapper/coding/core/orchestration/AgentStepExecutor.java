package com.glmapper.coding.core.orchestration;

import com.glmapper.agent.core.Agent;
import com.glmapper.agent.core.AgentEvent;
import com.glmapper.agent.core.AgentMessage;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.StopReason;
import com.glmapper.ai.api.TextContent;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.core.service.AgentToolFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentStepExecutor implements StepExecutor {
    private final AiRuntime aiRuntime;
    private final ModelCatalog modelCatalog;
    private final AgentToolFactory agentToolFactory;

    public AgentStepExecutor(AiRuntime aiRuntime, ModelCatalog modelCatalog, AgentToolFactory agentToolFactory) {
        this.aiRuntime = aiRuntime;
        this.modelCatalog = modelCatalog;
        this.agentToolFactory = agentToolFactory;
    }

    @Override
    public StepExecutorType type() {
        return StepExecutorType.AGENT;
    }

    @Override
    public PlanStepResult execute(
            ExecutionPlan plan,
            PlanStep step,
            PlanExecutionContext context,
            PlanExecutionObserver observer
    ) {
        Model model = modelCatalog.get(context.provider(), context.modelId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Model not found: " + context.provider() + "/" + context.modelId()));

        Agent agent = new Agent(aiRuntime, model, com.glmapper.agent.core.AgentOptions.defaults());
        agent.state().systemPrompt(buildSystemPrompt(context.systemPrompt()));
        agent.state().tools(agentToolFactory.createExecutionTools(
                context.namespace(),
                context.sessionId(),
                buildToolSelectionContext(context, plan, step)
        ));

        final int[] lastTextLen = {0};
        agent.subscribe(event -> handleEvent(plan, step, observer, event, lastTextLen));

        agent.prompt(buildStepPrompt(plan, step, context)).join();

        AgentMessage lastMessage = null;
        List<AgentMessage> messages = agent.state().messages();
        if (!messages.isEmpty()) {
            lastMessage = messages.get(messages.size() - 1);
        }

        if (lastMessage instanceof com.glmapper.agent.core.AgentAssistantMessage assistant) {
            if (assistant.stopReason() == StopReason.ERROR || assistant.stopReason() == StopReason.ABORTED) {
                String error = assistant.errorMessage() != null ? assistant.errorMessage() : "Agent step execution failed";
                return PlanStepResult.failure(error);
            }
            String text = extractText(assistant.content());
            return PlanStepResult.success(text.isBlank() ? step.successCriteria() : text);
        }

        return PlanStepResult.failure("Agent step did not return a final assistant message");
    }

    private void handleEvent(
            ExecutionPlan plan,
            PlanStep step,
            PlanExecutionObserver observer,
            AgentEvent event,
            int[] lastTextLen
    ) {
        if (event instanceof AgentEvent.MessageUpdate update
                && update.message() instanceof com.glmapper.agent.core.AgentAssistantMessage assistant) {
            String fullText = extractText(assistant.content());
            if (fullText.length() > lastTextLen[0]) {
                String delta = fullText.substring(lastTextLen[0]);
                lastTextLen[0] = fullText.length();
                observer.onStepOutput(plan, step, delta);
            }
            return;
        }

        if (event instanceof AgentEvent.MessageEnd end
                && end.message() instanceof com.glmapper.agent.core.AgentAssistantMessage assistant) {
            String fullText = extractText(assistant.content());
            if (fullText.length() > lastTextLen[0]) {
                String delta = fullText.substring(lastTextLen[0]);
                lastTextLen[0] = fullText.length();
                observer.onStepOutput(plan, step, delta);
            }
            return;
        }

        if (event instanceof AgentEvent.ToolExecutionStart toolStart) {
            observer.onToolStart(plan, step, toolStart.toolCallId(), toolStart.toolName());
            return;
        }

        if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
            observer.onToolEnd(
                    plan,
                    step,
                    toolEnd.toolCallId(),
                    toolEnd.toolName(),
                    extractText(toolEnd.result().content()),
                    toolEnd.isError()
            );
        }
    }

    private String buildSystemPrompt(String userSystemPrompt) {
        StringBuilder builder = new StringBuilder();
        if (userSystemPrompt != null && !userSystemPrompt.isBlank()) {
            builder.append(userSystemPrompt.trim()).append("\n\n");
        }
        builder.append("You are executing one workflow step inside delphi-agent.\n");
        builder.append("Complete only the current step.\n");
        builder.append("Do not re-plan the whole task.\n");
        builder.append("Do not start unrelated work.\n");
        builder.append("Use available tools only when they are clearly required for the current step.\n");
        builder.append("For straightforward coding or explanation requests, answer directly instead of invoking generic workflow skills.\n");
        builder.append("Return a concise result for the current step.");
        return builder.toString();
    }

    private String buildStepPrompt(ExecutionPlan plan, PlanStep step, PlanExecutionContext context) {
        return """
                执行当前任务步骤，不要重新规划。

                原始用户请求：
                %s

                总体目标：
                %s

                当前步骤：
                %s

                步骤说明：
                %s

                完成标准：
                %s

                只完成当前步骤，并给出当前步骤的执行结果。
                """.formatted(
                context.originalPrompt(),
                plan.goal(),
                step.title(),
                step.description(),
                step.successCriteria()
        );
    }

    private String buildToolSelectionContext(PlanExecutionContext context, ExecutionPlan plan, PlanStep step) {
        return String.join("\n",
                context.originalPrompt(),
                plan.goal(),
                step.title(),
                step.description(),
                step.successCriteria()
        );
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
}
