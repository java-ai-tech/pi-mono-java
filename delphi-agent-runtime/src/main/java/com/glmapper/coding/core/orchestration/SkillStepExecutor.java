package com.glmapper.coding.core.orchestration;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.coding.core.service.AgentToolFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CancellationException;

@Service
public class SkillStepExecutor implements StepExecutor {
    private final AgentToolFactory agentToolFactory;

    public SkillStepExecutor(AgentToolFactory agentToolFactory) {
        this.agentToolFactory = agentToolFactory;
    }

    @Override
    public StepExecutorType type() {
        return StepExecutorType.SKILL;
    }

    @Override
    public PlanStepResult execute(
            ExecutionPlan plan,
            PlanStep step,
            PlanExecutionContext context,
            PlanExecutionObserver observer
    ) {
        String toolName = String.valueOf(step.payload().getOrDefault("toolName", ""));
        String input = String.valueOf(step.payload().getOrDefault("input", ""));
        AgentTool tool = agentToolFactory.resolveTool(context.namespace(), context.sessionId(), toolName, false)
                .orElse(null);

        if (tool == null) {
            return PlanStepResult.failure("Skill tool not found: " + toolName);
        }

        AgentToolResult result = tool.execute(
                "skill-step-" + step.id(),
                Map.of("input", input),
                ignored -> { },
                new CancellationException("skill-step")
        ).join();

        String text = result.content().stream()
                .filter(com.glmapper.ai.api.TextContent.class::isInstance)
                .map(com.glmapper.ai.api.TextContent.class::cast)
                .map(com.glmapper.ai.api.TextContent::text)
                .reduce("", String::concat);
        observer.onStepOutput(plan, step, text);
        return PlanStepResult.success(text.isBlank() ? step.successCriteria() : text);
    }
}
