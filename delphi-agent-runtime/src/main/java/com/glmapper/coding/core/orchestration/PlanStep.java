package com.glmapper.coding.core.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlanStep {
    private final String id;
    private final String title;
    private final String description;
    private final String successCriteria;
    private final StepExecutorType executorType;
    private final Map<String, Object> payload;

    private PlanStepStatus status;
    private String resultSummary;
    private String errorMessage;

    public PlanStep(
            String id,
            String title,
            String description,
            String successCriteria,
            StepExecutorType executorType,
            Map<String, Object> payload
    ) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.successCriteria = successCriteria;
        this.executorType = executorType == null ? StepExecutorType.AGENT : executorType;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        this.status = PlanStepStatus.PENDING;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String successCriteria() {
        return successCriteria;
    }

    public StepExecutorType executorType() {
        return executorType;
    }

    public Map<String, Object> payload() {
        return new LinkedHashMap<>(payload);
    }

    public PlanStepStatus status() {
        return status;
    }

    public void status(PlanStepStatus status) {
        this.status = status;
    }

    public String resultSummary() {
        return resultSummary;
    }

    public void resultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
