package com.glmapper.coding.core.orchestration;

public interface StepExecutor {
    StepExecutorType type();

    PlanStepResult execute(
            ExecutionPlan plan,
            PlanStep step,
            PlanExecutionContext context,
            PlanExecutionObserver observer
    );
}
