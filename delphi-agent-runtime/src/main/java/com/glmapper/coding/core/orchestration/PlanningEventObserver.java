package com.glmapper.coding.core.orchestration;

public interface PlanningEventObserver {
    default void onPlanningStatus(String status) {
    }

    default void onToolStart(String toolCallId, String toolName) {
    }

    default void onToolEnd(String toolCallId, String toolName, String result, boolean isError) {
    }
}
