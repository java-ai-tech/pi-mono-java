package com.glmapper.coding.core.tools.policy;

public interface ToolPolicy {
    ToolPolicyDecision evaluate(ToolRuntimeContext context, ToolInventory.Item toolItem);
}

