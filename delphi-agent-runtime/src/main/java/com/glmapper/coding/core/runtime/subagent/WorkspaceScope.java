package com.glmapper.coding.core.runtime.subagent;

import java.util.Locale;

public enum WorkspaceScope {
    SESSION,
    PROJECT,
    EPHEMERAL;

    public static WorkspaceScope fromValue(String value, WorkspaceScope fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return WorkspaceScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}

