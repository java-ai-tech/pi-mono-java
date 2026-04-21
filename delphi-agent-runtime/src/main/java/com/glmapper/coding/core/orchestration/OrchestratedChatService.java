package com.glmapper.coding.core.orchestration;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OrchestratedChatService {
    private final PlannerService plannerService;
    private final PlanDispatcher planDispatcher;
    private final PlanResultAggregator planResultAggregator;

    public OrchestratedChatService(
            PlannerService plannerService,
            PlanDispatcher planDispatcher,
            PlanResultAggregator planResultAggregator
    ) {
        this.plannerService = plannerService;
        this.planDispatcher = planDispatcher;
        this.planResultAggregator = planResultAggregator;
    }

    public void stream(
            String namespace,
            String prompt,
            String provider,
            String modelId,
            String systemPrompt,
            String sessionId,
            ChatEventSink sink
    ) {
        String effectiveSessionId = sessionId != null && !sessionId.isBlank()
                ? sessionId
                : "chat-" + System.currentTimeMillis();

        StringBuilder assistantText = new StringBuilder();
        emit(sink, "agent_start", Map.of("type", "agent_start"));
        emit(sink, "turn_start", Map.of("type", "turn_start"));
        emit(sink, "message_start", Map.of("type", "message_start", "role", "assistant"));
        emit(sink, "command_received", Map.of(
                "type", "command_received",
                "prompt", prompt,
                "sessionId", effectiveSessionId
        ));

        try {
            appendText(sink, assistantText, "开始分析任务并生成执行计划。\n");
            emit(sink, "planning_started", Map.of("type", "planning_started", "prompt", prompt));

            ExecutionPlan plan = plannerService.createPlan(
                    namespace,
                    effectiveSessionId,
                    provider,
                    modelId,
                    systemPrompt,
                    prompt,
                    new PlanningEventObserver() {
                        @Override
                        public void onPlanningStatus(String status) {
                            emit(sink, "planning_progress", Map.of(
                                    "type", "planning_progress",
                                    "message", status == null ? "" : status
                            ));
                        }

                        @Override
                        public void onToolStart(String toolCallId, String toolName) {
                            emit(sink, "tool_start", Map.of(
                                    "type", "tool_start",
                                    "toolCallId", toolCallId,
                                    "toolName", toolName,
                                    "phase", "planning"
                            ));
                        }

                        @Override
                        public void onToolEnd(String toolCallId, String toolName, String result, boolean isError) {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("type", "tool_end");
                            payload.put("toolCallId", toolCallId);
                            payload.put("toolName", toolName);
                            payload.put("result", result == null ? "" : result);
                            payload.put("isError", isError);
                            payload.put("phase", "planning");
                            emit(sink, "tool_end", payload);
                        }
                    }
            );
            emit(sink, "planning_completed", planData(plan));
            emit(sink, "execution_started", Map.of(
                    "type", "execution_started",
                    "planId", plan.id(),
                    "goal", plan.goal(),
                    "totalSteps", plan.steps().size()
            ));

            appendText(sink, assistantText,
                    "已生成 %d 个执行步骤，开始逐项处理。\n".formatted(plan.steps().size()));

            PlanExecutionContext context = new PlanExecutionContext(
                    namespace,
                    effectiveSessionId,
                    provider,
                    modelId,
                    systemPrompt,
                    prompt,
                    plan.goal()
            );

            planDispatcher.executePlan(plan, context, new StreamingPlanObserver(sink, assistantText));
            String finalSummary = planResultAggregator.summarize(plan);
            plan.finalSummary(finalSummary);

            if (!finalSummary.isBlank()) {
                appendText(sink, assistantText, "\n最终结果：\n" + finalSummary);
            }

            emit(sink, "message_end", Map.of(
                    "type", "message_end",
                    "role", "assistant",
                    "text", assistantText.toString()
            ));
            emit(sink, "turn_end", Map.of("type", "turn_end"));
            emit(sink, "done", Map.of("type", "done", "planStatus", plan.status().name()));
        } catch (Exception e) {
            emit(sink, "message_end", Map.of(
                    "type", "message_end",
                    "role", "assistant",
                    "text", assistantText.toString()
            ));
            emit(sink, "turn_end", Map.of("type", "turn_end"));
            emit(sink, "error", Map.of(
                    "type", "error",
                    "message", e.getMessage() == null ? "执行失败" : e.getMessage()
            ));
            emit(sink, "done", Map.of("type", "done", "planStatus", PlanStatus.FAILED.name()));
        }
    }

    private Map<String, Object> planData(ExecutionPlan plan) {
        return Map.of(
                "type", "planning_completed",
                "planId", plan.id(),
                "goal", plan.goal(),
                "status", plan.status().name(),
                "steps", plan.steps().stream().map(step -> Map.of(
                        "id", step.id(),
                        "title", step.title(),
                        "description", step.description(),
                        "successCriteria", step.successCriteria(),
                        "executorType", step.executorType().name(),
                        "status", step.status().name()
                )).toList()
        );
    }

    private void appendText(ChatEventSink sink, StringBuilder assistantText, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        assistantText.append(delta);
        emit(sink, "text", Map.of("type", "text", "delta", delta));
    }

    private void emit(ChatEventSink sink, String name, Map<String, Object> data) {
        sink.emit(name, data);
    }

    private final class StreamingPlanObserver implements PlanExecutionObserver {
        private final ChatEventSink sink;
        private final StringBuilder assistantText;

        private StreamingPlanObserver(ChatEventSink sink, StringBuilder assistantText) {
            this.sink = sink;
            this.assistantText = assistantText;
        }

        @Override
        public void onStepStarted(ExecutionPlan plan, PlanStep step, int stepIndex, int totalSteps) {
            emit(sink, "step_started", Map.of(
                    "type", "step_started",
                    "planId", plan.id(),
                    "stepId", step.id(),
                    "title", step.title(),
                    "index", stepIndex + 1,
                    "total", totalSteps
            ));
            assistantText.append("\n[%d/%d] 开始执行：%s\n".formatted(stepIndex + 1, totalSteps, step.title()));
        }

        @Override
        public void onStepOutput(ExecutionPlan plan, PlanStep step, String delta) {
            if (delta == null || delta.isBlank()) {
                return;
            }
            emit(sink, "step_output", Map.of(
                    "type", "step_output",
                    "planId", plan.id(),
                    "stepId", step.id(),
                    "delta", delta
            ));
            // Only append to assistantText for final summary, do NOT emit "text" event
            // to avoid double-rendering on the frontend (step_output + text both append to currentText)
            assistantText.append(delta);
        }

        @Override
        public void onToolStart(ExecutionPlan plan, PlanStep step, String toolCallId, String toolName) {
            emit(sink, "tool_start", Map.of(
                    "type", "tool_start",
                    "toolCallId", toolCallId,
                    "toolName", toolName,
                    "stepId", step.id()
            ));
        }

        @Override
        public void onToolEnd(ExecutionPlan plan, PlanStep step, String toolCallId, String toolName, String result, boolean isError) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "tool_end");
            payload.put("toolCallId", toolCallId);
            payload.put("toolName", toolName);
            payload.put("stepId", step.id());
            payload.put("result", result == null ? "" : result);
            payload.put("isError", isError);
            emit(sink, "tool_end", payload);
        }

        @Override
        public void onStepCompleted(ExecutionPlan plan, PlanStep step, PlanStepResult result) {
            emit(sink, "step_completed", Map.of(
                    "type", "step_completed",
                    "planId", plan.id(),
                    "stepId", step.id(),
                    "summary", result.summary() == null ? "" : result.summary()
            ));
            assistantText.append("\n步骤完成：%s\n".formatted(step.title()));
        }

        @Override
        public void onStepFailed(ExecutionPlan plan, PlanStep step, PlanStepResult result) {
            emit(sink, "step_failed", Map.of(
                    "type", "step_failed",
                    "planId", plan.id(),
                    "stepId", step.id(),
                    "error", result.errorMessage() == null ? "执行失败" : result.errorMessage()
            ));
            assistantText.append("\n步骤失败：%s\n原因：%s\n".formatted(
                    step.title(),
                    result.errorMessage() == null ? "执行失败" : result.errorMessage()
            ));
        }
    }
}
