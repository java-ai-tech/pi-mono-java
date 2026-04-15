package com.glmapper.coding.core.orchestration;

import org.springframework.stereotype.Service;

@Service
public class PlanDispatcher {
    private final StepExecutorRegistry stepExecutorRegistry;

    public PlanDispatcher(StepExecutorRegistry stepExecutorRegistry) {
        this.stepExecutorRegistry = stepExecutorRegistry;
    }

    public ExecutionPlan executePlan(
            ExecutionPlan plan,
            PlanExecutionContext context,
            PlanExecutionObserver observer
    ) {
        plan.status(PlanStatus.RUNNING);

        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            plan.currentStepIndex(i);
            step.status(PlanStepStatus.RUNNING);
            observer.onStepStarted(plan, step, i, plan.steps().size());

            StepExecutor executor = stepExecutorRegistry.get(step.executorType());
            PlanStepResult result = executor.execute(plan, step, context, observer);
            if (result.success()) {
                step.status(PlanStepStatus.SUCCEEDED);
                step.resultSummary(result.summary());
                observer.onStepCompleted(plan, step, result);
                continue;
            }

            step.status(PlanStepStatus.FAILED);
            step.errorMessage(result.errorMessage());
            plan.status(PlanStatus.FAILED);
            plan.errorMessage(result.errorMessage());
            observer.onStepFailed(plan, step, result);
            return plan;
        }

        plan.status(PlanStatus.SUCCEEDED);
        return plan;
    }
}
