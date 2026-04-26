package com.glmapper.coding.core.tools.policy;

import com.glmapper.agent.core.AgentTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolPolicyPipeline {

    private final TenantToolPolicyResolver tenantToolPolicyResolver;
    private final ToolExecutionWrapper toolExecutionWrapper;
    private final ToolAuditWrapper toolAuditWrapper;

    public ToolPolicyPipeline(TenantToolPolicyResolver tenantToolPolicyResolver,
                              ToolExecutionWrapper toolExecutionWrapper,
                              ToolAuditWrapper toolAuditWrapper) {
        this.tenantToolPolicyResolver = tenantToolPolicyResolver;
        this.toolExecutionWrapper = toolExecutionWrapper;
        this.toolAuditWrapper = toolAuditWrapper;
    }

    public List<AgentTool> apply(ToolRuntimeContext context, List<ToolInventory.Item> items) {
        ToolPolicy policy = tenantToolPolicyResolver.resolve(context);
        List<AgentTool> resolved = new ArrayList<>();

        for (ToolInventory.Item item : items) {
            ToolPolicyDecision decision = policy.evaluate(context, item);
            toolAuditWrapper.onPolicyDecision(context, item, decision);
            if (decision.level() == ToolAccessLevel.DENY) {
                resolved.add(toolExecutionWrapper.deny(context, item, decision));
                continue;
            }
            resolved.add(toolExecutionWrapper.wrap(context, item));
        }
        return resolved;
    }
}
