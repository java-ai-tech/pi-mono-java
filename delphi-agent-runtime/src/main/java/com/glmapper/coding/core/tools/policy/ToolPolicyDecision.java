package com.glmapper.coding.core.tools.policy;

public record ToolPolicyDecision(ToolAccessLevel level, String reason) {
    public static ToolPolicyDecision allow() {
        return new ToolPolicyDecision(ToolAccessLevel.ALLOW, null);
    }

    public static ToolPolicyDecision deny(String reason) {
        return new ToolPolicyDecision(ToolAccessLevel.DENY, reason == null ? "denied_by_policy" : reason);
    }
}

