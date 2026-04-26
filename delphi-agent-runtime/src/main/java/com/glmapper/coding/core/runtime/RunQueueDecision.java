package com.glmapper.coding.core.runtime;

public record RunQueueDecision(RunQueueDecisionType type, String reason) {
    public static RunQueueDecision runNow() {
        return new RunQueueDecision(RunQueueDecisionType.RUN_NOW, "");
    }
}

