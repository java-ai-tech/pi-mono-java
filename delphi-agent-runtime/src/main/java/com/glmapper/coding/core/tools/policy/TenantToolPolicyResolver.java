package com.glmapper.coding.core.tools.policy;

import com.glmapper.coding.core.runtime.subagent.SubagentRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class TenantToolPolicyResolver {

    private static final int MAX_SUBAGENT_DEPTH = 2;

    /**
     * 是否对 ORCHESTRATOR 启用严格模式。
     * 默认 false：主代理拥有全部类目权限（适合单机/演示/可信场景）。
     * 设为 true：主代理仅能 READONLY / INSTRUCTIONAL / ORCHESTRATION，
     *           MUTATING / EXECUTABLE 必须通过 spawn CODER/TESTER 完成。
     */
    @Value("${pi.tool-policy.orchestrator-strict:false}")
    private boolean orchestratorStrict;

    public ToolPolicy resolve(ToolRuntimeContext context) {
        Set<ToolCategory> allowed = allowedCategories(context.agentRole());
        return (runtimeContext, toolItem) -> {
            if (runtimeContext.tenantId() != null && !runtimeContext.tenantId().isBlank()
                    && !runtimeContext.tenantId().equals(runtimeContext.namespace())) {
                return ToolPolicyDecision.deny("tenant_namespace_mismatch");
            }
            if (!allowed.contains(toolItem.category())) {
                return ToolPolicyDecision.deny("category_denied_for_role");
            }
            if (toolItem.category() == ToolCategory.ORCHESTRATION && runtimeContext.depth() >= MAX_SUBAGENT_DEPTH) {
                return ToolPolicyDecision.deny("subagent_depth_exceeded");
            }
            if (toolItem.category() == ToolCategory.ORCHESTRATION
                    && runtimeContext.agentRole() != SubagentRole.ORCHESTRATOR) {
                return ToolPolicyDecision.deny("orchestration_only_for_orchestrator");
            }
            if (runtimeContext.agentRole() == SubagentRole.TESTER && toolItem.category() == ToolCategory.MUTATING) {
                return ToolPolicyDecision.deny("tester_mutating_denied");
            }
            return ToolPolicyDecision.allow();
        };
    }

    private Set<ToolCategory> allowedCategories(SubagentRole role) {
        SubagentRole effective = role == null ? SubagentRole.ORCHESTRATOR : role;
        return switch (effective) {
            case ORCHESTRATOR -> orchestratorStrict
                    ? EnumSet.of(
                            ToolCategory.READONLY,
                            ToolCategory.INSTRUCTIONAL,
                            ToolCategory.ORCHESTRATION
                    )
                    : EnumSet.allOf(ToolCategory.class);
            case PLANNER, RESEARCHER -> EnumSet.of(
                    ToolCategory.READONLY,
                    ToolCategory.INSTRUCTIONAL
            );
            case REVIEWER -> EnumSet.of(ToolCategory.READONLY);
            case CODER -> EnumSet.of(
                    ToolCategory.READONLY,
                    ToolCategory.MUTATING,
                    ToolCategory.EXECUTABLE,
                    ToolCategory.INSTRUCTIONAL
            );
            case TESTER -> EnumSet.of(
                    ToolCategory.READONLY,
                    ToolCategory.EXECUTABLE,
                    ToolCategory.INSTRUCTIONAL
            );
        };
    }
}

