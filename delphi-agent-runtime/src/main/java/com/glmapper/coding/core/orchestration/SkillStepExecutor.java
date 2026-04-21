package com.glmapper.coding.core.orchestration;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.coding.core.service.AgentToolFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
        if (toolName.isBlank()) {
            return PlanStepResult.failure("Skill step is missing toolName payload");
        }

        // Normalize toolName: support both "deploy" and "skill_deploy" formats
        String normalizedToolName = toolName.startsWith("skill_") ? toolName : "skill_" + toolName;

        Map<String, Object> toolArgs = buildToolArgs(step.payload());
        AgentTool tool = agentToolFactory.resolveTool(context.namespace(), context.sessionId(), normalizedToolName, false)
                .orElse(null);

        if (tool == null) {
            return PlanStepResult.failure("Skill tool not found: " + toolName + " (normalized: " + normalizedToolName + ")");
        }

        AgentToolResult result = tool.execute(
                "skill-step-" + step.id(),
                toolArgs,
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

    private Map<String, Object> buildToolArgs(Map<String, Object> payload) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if ("toolName".equals(key) || "description".equals(key) || "expectedOutput".equals(key)) {
                continue;
            }
            args.put(key, entry.getValue());
        }
        return args;
    }
}
