package com.glmapper.coding.core.orchestration;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.service.AgentToolFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class SkillStepExecutorTest {

    @Test
    void shouldPassThroughStructuredSkillArgs() {
        AgentToolFactory factory = Mockito.mock(AgentToolFactory.class);
        AgentTool tool = Mockito.mock(AgentTool.class);
        Mockito.when(factory.resolveTool("tenant-a", "s-1", "skill_deploy", false))
                .thenReturn(Optional.of(tool));
        Mockito.when(tool.execute(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new AgentToolResult(List.of(new TextContent("done", null)), Map.of())
                ));

        SkillStepExecutor executor = new SkillStepExecutor(factory);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", "skill_deploy");
        payload.put("description", "deploy app");
        payload.put("expectedOutput", "deployment done");
        payload.put("input", "--env staging");
        payload.put("env", "staging");
        payload.put("service", "api");

        PlanStep step = new PlanStep(
                "step-1",
                "Deploy",
                "Run deploy skill",
                "deployment done",
                StepExecutorType.SKILL,
                payload
        );

        ExecutionPlan plan = new ExecutionPlan("plan-1", "deploy goal", List.of(step));
        PlanExecutionContext context = new PlanExecutionContext("tenant-a", "s-1", "p", "m", "", "prompt", "goal");

        PlanStepResult result = executor.execute(plan, step, context, new PlanExecutionObserver() { });

        assertEquals(true, result.success());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(tool).execute(eq("skill-step-step-1"), argsCaptor.capture(), any(), any());

        Map<String, Object> toolArgs = argsCaptor.getValue();
        assertEquals("--env staging", toolArgs.get("input"));
        assertEquals("staging", toolArgs.get("env"));
        assertEquals("api", toolArgs.get("service"));
        assertFalse(toolArgs.containsKey("toolName"));
        assertFalse(toolArgs.containsKey("description"));
        assertFalse(toolArgs.containsKey("expectedOutput"));
    }

    @Test
    void shouldFailWhenToolNameMissing() {
        AgentToolFactory factory = Mockito.mock(AgentToolFactory.class);
        SkillStepExecutor executor = new SkillStepExecutor(factory);

        PlanStep step = new PlanStep(
                "step-1",
                "Deploy",
                "Run deploy skill",
                "deployment done",
                StepExecutorType.SKILL,
                Map.of("input", "--env staging")
        );
        ExecutionPlan plan = new ExecutionPlan("plan-1", "deploy goal", List.of(step));
        PlanExecutionContext context = new PlanExecutionContext("tenant-a", "s-1", "p", "m", "", "prompt", "goal");

        PlanStepResult result = executor.execute(plan, step, context, new PlanExecutionObserver() { });

        assertEquals(false, result.success());
        assertEquals("Skill step is missing toolName payload", result.errorMessage());
        Mockito.verifyNoInteractions(factory);
    }
}
