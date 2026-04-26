package com.glmapper.coding.core.runtime;

public enum RunFailureType {
    ABORTED,
    MODEL_ERROR,
    TOOL_ERROR,
    TOOL_VALIDATION_ERROR,
    TOOL_POLICY_DENIED,
    SUBAGENT_FAILED,
    QUOTA_REJECTED,
    TIMEOUT,
    CONTEXT_OVERFLOW,
    TENANT_ACCESS_DENIED,
    UNKNOWN
}

