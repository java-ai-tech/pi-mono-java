package com.glmapper.coding.core.tools.policy;

import com.glmapper.coding.core.runtime.RuntimeAuditService;
import org.springframework.stereotype.Component;

@Component
public class ToolAuditWrapper {

    private final RuntimeAuditService runtimeAuditService;

    public ToolAuditWrapper(RuntimeAuditService runtimeAuditService) {
        this.runtimeAuditService = runtimeAuditService;
    }

    public void onPolicyDecision(ToolRuntimeContext context,
                                 ToolInventory.Item item,
                                 ToolPolicyDecision decision) {
        runtimeAuditService.recordPolicyDecision(new RuntimeAuditService.ToolRuntimeAuditRecord(
                context.namespace(),
                context.userId(),
                context.sessionId(),
                context.runId(),
                context.subagentId(),
                item.tool().name(),
                item.category().name(),
                decision.level().name(),
                decision.reason(),
                0,
                item.resource()
        ));
    }

    public void onExecution(ToolRuntimeContext context,
                            ToolInventory.Item item,
                            String decision,
                            String reason,
                            long durationMs) {
        runtimeAuditService.recordToolExecution(new RuntimeAuditService.ToolRuntimeAuditRecord(
                context.namespace(),
                context.userId(),
                context.sessionId(),
                context.runId(),
                context.subagentId(),
                item.tool().name(),
                item.category().name(),
                decision,
                reason,
                durationMs,
                item.resource()
        ));
    }
}

