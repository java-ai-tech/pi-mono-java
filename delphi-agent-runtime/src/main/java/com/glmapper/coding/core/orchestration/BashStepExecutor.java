package com.glmapper.coding.core.orchestration;

import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;
import org.springframework.stereotype.Service;

@Service
public class BashStepExecutor implements StepExecutor {
    private final ExecutionBackend executionBackend;

    public BashStepExecutor(ExecutionBackend executionBackend) {
        this.executionBackend = executionBackend;
    }

    @Override
    public StepExecutorType type() {
        return StepExecutorType.BASH;
    }

    @Override
    public PlanStepResult execute(
            ExecutionPlan plan,
            PlanStep step,
            PlanExecutionContext context,
            PlanExecutionObserver observer
    ) {
        String command = String.valueOf(step.payload().getOrDefault("command", ""));
        if (command.isBlank()) {
            return PlanStepResult.failure("Bash step is missing command payload");
        }

        ExecutionResult result = executionBackend.execute(
                new ExecutionContext(context.namespace(), context.sessionId(), null),
                command,
                ExecutionOptions.defaults()
        );
        String output = result.stdout();
        if (!result.stderr().isBlank()) {
            output = output + "\n" + result.stderr();
        }

        observer.onStepOutput(plan, step, output);
        if (!result.isSuccess()) {
            return PlanStepResult.failure(output.isBlank() ? "Bash step execution failed" : output);
        }
        return PlanStepResult.success(output.isBlank() ? step.successCriteria() : output);
    }
}
