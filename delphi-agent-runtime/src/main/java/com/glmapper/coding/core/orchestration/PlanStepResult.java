package com.glmapper.coding.core.orchestration;

public record PlanStepResult(
        boolean success,
        String summary,
        String errorMessage
) {
    public static PlanStepResult success(String summary) {
        return new PlanStepResult(true, summary, null);
    }

    public static PlanStepResult failure(String errorMessage) {
        return new PlanStepResult(false, null, errorMessage);
    }
}
