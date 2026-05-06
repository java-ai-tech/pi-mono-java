package com.glmapper.coding.core.runtime.subagent;

import java.util.Locale;

public enum SubagentRole {
    /**
     * Orchestrator：负责整体协调和管理
     */
    ORCHESTRATOR,
    /**
     * PLANNER：负责制定详细的计划和策略
     */
    PLANNER,
    /**
     * RESEARCHER：负责进行深入的研究和信息收集
     */
    RESEARCHER,
    /**
     * REVIEWER：负责审查和评估其他子Agent的工作成果
     */
    REVIEWER,
    /**
     * CODER：负责具体的编码和实现工作
     */
    CODER,
    /**
     * TESTER：负责测试和验证其他子Agent的工作成果
     */
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

