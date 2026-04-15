package com.glmapper.coding.core.orchestration;

import java.util.ArrayList;
import java.util.List;

public final class ExecutionPlan {
    private final String id;
    private final String goal;
    private final List<PlanStep> steps;

    private PlanStatus status;
    private int currentStepIndex;
    private String finalSummary;
    private String errorMessage;

    public ExecutionPlan(String id, String goal, List<PlanStep> steps) {
        this.id = id;
        this.goal = goal;
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
        this.status = PlanStatus.PLANNING;
        this.currentStepIndex = 0;
    }

    public String id() {
        return id;
    }

    public String goal() {
        return goal;
    }

    public List<PlanStep> steps() {
        return steps;
    }

    public PlanStatus status() {
        return status;
    }

    public void status(PlanStatus status) {
        this.status = status;
    }

    public int currentStepIndex() {
        return currentStepIndex;
    }

    public void currentStepIndex(int currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }

    public String finalSummary() {
        return finalSummary;
    }

    public void finalSummary(String finalSummary) {
        this.finalSummary = finalSummary;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
