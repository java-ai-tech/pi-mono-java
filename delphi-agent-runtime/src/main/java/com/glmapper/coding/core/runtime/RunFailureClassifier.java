package com.glmapper.coding.core.runtime;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

@Component
public class RunFailureClassifier {

    public RunFailureType classify(Throwable throwable) {
        Throwable root = unwrap(throwable);
        if (root == null) {
            return RunFailureType.UNKNOWN;
        }
        if (root instanceof TimeoutException) {
            return RunFailureType.TIMEOUT;
        }
        if (root instanceof TenantRuntimeGuard.QuotaRejectedException) {
            return RunFailureType.QUOTA_REJECTED;
        }
        if (root instanceof SecurityException) {
            return RunFailureType.TENANT_ACCESS_DENIED;
        }

        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return RunFailureType.UNKNOWN;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("aborted") || lower.contains("cancelled")) {
            return RunFailureType.ABORTED;
        }
        if (lower.contains("quota")) {
            return RunFailureType.QUOTA_REJECTED;
        }
        if (lower.contains("validation") || lower.contains("invalid")) {
            return RunFailureType.TOOL_VALIDATION_ERROR;
        }
        if (lower.contains("policy denied") || lower.contains("denied by policy")) {
            return RunFailureType.TOOL_POLICY_DENIED;
        }
        if (lower.contains("tool")) {
            return RunFailureType.TOOL_ERROR;
        }
        if (lower.contains("context") && lower.contains("overflow")) {
            return RunFailureType.CONTEXT_OVERFLOW;
        }
        if (lower.contains("subagent")) {
            return RunFailureType.SUBAGENT_FAILED;
        }
        if (lower.contains("model") || lower.contains("provider") || lower.contains("llm")) {
            return RunFailureType.MODEL_ERROR;
        }
        return RunFailureType.UNKNOWN;
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

