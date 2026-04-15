package com.glmapper.coding.core.orchestration;

public interface PlanExecutionObserver {
    default void onStepStarted(ExecutionPlan plan, PlanStep step, int stepIndex, int totalSteps) {
    }

    default void onStepOutput(ExecutionPlan plan, PlanStep step, String delta) {
    }

    default void onToolStart(ExecutionPlan plan, PlanStep step, String toolCallId, String toolName) {
    }

    default void onToolEnd(ExecutionPlan plan, PlanStep step, String toolCallId, String toolName, String result, boolean isError) {
    }

    default void onStepCompleted(ExecutionPlan plan, PlanStep step, PlanStepResult result) {
    }

    default void onStepFailed(ExecutionPlan plan, PlanStep step, PlanStepResult result) {
    }
}
