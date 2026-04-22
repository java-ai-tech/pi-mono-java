package com.glmapper.coding.core.orchestration;

import com.glmapper.coding.core.service.AgentSessionRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;

class OrchestratedChatServiceTest {

    @Test
    void streamShouldPersistOrchestratedTurnOnSuccess() {
        PlannerService plannerService = Mockito.mock(PlannerService.class);
        PlanDispatcher planDispatcher = Mockito.mock(PlanDispatcher.class);
        PlanResultAggregator aggregator = Mockito.mock(PlanResultAggregator.class);
        AgentSessionRuntime sessionRuntime = Mockito.mock(AgentSessionRuntime.class);

        PlanStep step = new PlanStep(
                "step-1",
                "实现",
                "实现功能",
                "完成",
                StepExecutorType.AGENT,
                Map.of("description", "实现功能")
        );
        ExecutionPlan plan = new ExecutionPlan("plan-1", "完成任务", List.of(step));

        Mockito.when(plannerService.createPlan(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(plan);
        Mockito.when(planDispatcher.executePlan(any(), any(), any())).thenAnswer(invocation -> {
            plan.status(PlanStatus.SUCCEEDED);
            return plan;
        });
        Mockito.when(aggregator.summarize(any())).thenReturn("全部完成");

        OrchestratedChatService service = new OrchestratedChatService(
                plannerService,
                planDispatcher,
                aggregator,
                sessionRuntime
        );

        List<String> eventNames = new ArrayList<>();
        service.stream(
                "demo",
                "请实现一个功能",
                "deepseek",
                "deepseek-chat",
                "",
                "s-1",
                (eventName, data) -> eventNames.add(eventName)
        );

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(sessionRuntime).persistOrchestrationTurn(
                eq("s-1"),
                eq("demo"),
                eq("请实现一个功能"),
                textCaptor.capture()
        );

        assertTrue(textCaptor.getValue().contains("最终结果："));
        assertTrue(textCaptor.getValue().contains("全部完成"));
        assertTrue(eventNames.contains("done"));
    }

    @Test
    void streamShouldPersistFailureTurnWhenOrchestrationFails() {
        PlannerService plannerService = Mockito.mock(PlannerService.class);
        PlanDispatcher planDispatcher = Mockito.mock(PlanDispatcher.class);
        PlanResultAggregator aggregator = Mockito.mock(PlanResultAggregator.class);
        AgentSessionRuntime sessionRuntime = Mockito.mock(AgentSessionRuntime.class);

        Mockito.when(plannerService.createPlan(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("boom"));

        OrchestratedChatService service = new OrchestratedChatService(
                plannerService,
                planDispatcher,
                aggregator,
                sessionRuntime
        );

        List<Map<String, Object>> events = new ArrayList<>();
        service.stream(
                "demo",
                "请实现一个功能",
                "deepseek",
                "deepseek-chat",
                "",
                "s-2",
                (eventName, data) -> events.add(Map.of("name", eventName, "data", data))
        );

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(sessionRuntime).persistOrchestrationTurn(
                eq("s-2"),
                eq("demo"),
                eq("请实现一个功能"),
                textCaptor.capture()
        );
        assertTrue(textCaptor.getValue().contains("编排执行失败：boom"));

        boolean hasFailedDone = events.stream().anyMatch(e -> {
            if (!"done".equals(e.get("name"))) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) e.get("data");
            return PlanStatus.FAILED.name().equals(data.get("planStatus"));
        });
        assertTrue(hasFailedDone);
    }
}
