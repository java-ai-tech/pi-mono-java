package com.glmapper.coding.core.orchestration;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class StepExecutorRegistry {
    private final Map<StepExecutorType, StepExecutor> executors = new EnumMap<>(StepExecutorType.class);

    public StepExecutorRegistry(List<StepExecutor> stepExecutors) {
        if (stepExecutors != null) {
            for (StepExecutor executor : stepExecutors) {
                executors.put(executor.type(), executor);
            }
        }
    }

    public StepExecutor get(StepExecutorType type) {
        StepExecutor executor = executors.get(type);
        if (executor == null) {
            throw new IllegalArgumentException("No step executor registered for type: " + type);
        }
        return executor;
    }
}
