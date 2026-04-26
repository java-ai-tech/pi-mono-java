package com.glmapper.coding.core.runtime.subagent;

import java.util.Locale;

public enum SubagentRole {
    ORCHESTRATOR,
    PLANNER,
    RESEARCHER,
    REVIEWER,
    CODER,
    TESTER;

    public static SubagentRole fromValue(String value, SubagentRole fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return SubagentRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}

