package com.glmapper.coding.core.tools.policy;

import com.glmapper.agent.core.AgentTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ToolPolicyPipeline 工具策略评估的核心组件
 * 负责将工具运行时上下文和工具清单作为输入，经过一系列的策略评估和审计处理，最终输出一个经过筛选和包装的 AgentTool 列表供代理执行。
 * ToolPolicyPipeline 通过整合 TenantToolPolicyResolver、ToolExecutionWrapper 和 ToolAuditWrapper 实现工具访问控制、执行包装和审计记录的功能，使得工具的使用更加安全、透明和可控。
 *
 * @Classname ToolPolicyPipeline
 * @author glmapper
 */
@Component
public class ToolPolicyPipeline {

    /**
     * 根据工具运行时上下文解析出适用的工具策略
     */
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
