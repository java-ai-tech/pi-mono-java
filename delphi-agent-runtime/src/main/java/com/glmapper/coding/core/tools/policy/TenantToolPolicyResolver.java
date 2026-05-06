package com.glmapper.coding.core.tools.policy;

import com.glmapper.coding.core.runtime.subagent.SubagentRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * TenantToolPolicyResolver 负责根据工具运行时上下文中的子Agent角色和其他相关信息来解析适用的工具访问策略的组件。
 * 通过定义不同子Agent角色对应的工具类别权限，以及一些额外的限制（如子Agent深度限制和特定类别的访问限制），
 * 来动态地生成一个 ToolPolicy 实例，该实例在工具执行前被用来评估每个工具调用是否被允许，从而实现细粒度的工具访问控制。
 *
 * @author glmapper
 * @Classname TenantToolPolicyResolver
 */
@Component
public class TenantToolPolicyResolver {

    private static final int MAX_SUBAGENT_DEPTH = 2;

    /**
     * 是否对 ORCHESTRATOR 启用严格模式。
     * 默认 false：主代理拥有全部类目权限（适合单机/演示/可信场景）。
     * 设为 true：主代理仅能 READONLY / INSTRUCTIONAL / ORCHESTRATION，
     * MUTATING / EXECUTABLE 必须通过 spawn CODER/TESTER 完成。
     */
    @Value("${pi.tool-policy.orchestrator-strict:false}")
    private boolean orchestratorStrict;

    /**
     * 根据工具运行时上下文解析出适用的工具访问策略
     *
     * @param context 工具运行时上下文，包含子 Agent 角色、深度、租户信息等
     * @return 适用于当前上下文的工具访问策略实例
     */
    public ToolPolicy resolve(ToolRuntimeContext context) {
        // 计算出当前 Agent 角色允许访问的工具类别集合
        Set<ToolCategory> allowed = allowedCategories(context.agentRole());
        // 返回一个基于上下文和工具类别的动态评估函数，用于在工具执行前进行访问控制决策
        return (runtimeContext, toolItem) -> {
            if (runtimeContext.tenantId() != null && !runtimeContext.tenantId().isBlank() && !runtimeContext.tenantId()
                    .equals(runtimeContext.namespace())) {
                return ToolPolicyDecision.deny("tenant_namespace_mismatch");
            }
            if (!allowed.contains(toolItem.category())) {
                return ToolPolicyDecision.deny("category_denied_for_role");
            }
            if (toolItem.category() == ToolCategory.ORCHESTRATION && runtimeContext.depth() >= MAX_SUBAGENT_DEPTH) {
                return ToolPolicyDecision.deny("subagent_depth_exceeded");
            }
            if (toolItem.category() == ToolCategory.ORCHESTRATION && runtimeContext.agentRole() != SubagentRole.ORCHESTRATOR) {
                return ToolPolicyDecision.deny("orchestration_only_for_orchestrator");
            }
            if (runtimeContext.agentRole() == SubagentRole.TESTER && toolItem.category() == ToolCategory.MUTATING) {
                return ToolPolicyDecision.deny("tester_mutating_denied");
            }
            return ToolPolicyDecision.allow();
        };
    }

    /**
     * 根据子 Agent 角色返回允许访问的工具类别集合
     *
     * - ORCHESTRATOR：默认拥有全部权限，或仅限于 READONLY / INSTRUCTIONAL / ORCHESTRATION（取决于 orchestratorStrict 配置）
     * - PLANNER / RESEARCHER：仅限于 READONLY / INSTRUCTIONAL
     * - REVIEWER：仅限于 READONLY
     * - CODER：允许 READONLY / MUTATING / EXECUTABLE / INSTRUCTIONAL
     * - TESTER：允许 READONLY / EXECUTABLE / INSTRUCTIONAL，但禁止 MUTATING
     *
     * @param role 子 Agent 角色，可能为 null（默认为 ORCHESTRATOR）
     * @return 该角色允许访问的工具类别集合
     */
    private Set<ToolCategory> allowedCategories(SubagentRole role) {
        SubagentRole effective = role == null ? SubagentRole.ORCHESTRATOR : role;
        return switch (effective) {
            case ORCHESTRATOR ->
                    orchestratorStrict ? EnumSet.of(ToolCategory.READONLY, ToolCategory.INSTRUCTIONAL, ToolCategory.ORCHESTRATION) : EnumSet.allOf(ToolCategory.class);
            case PLANNER, RESEARCHER -> EnumSet.of(ToolCategory.READONLY, ToolCategory.INSTRUCTIONAL);
            case REVIEWER -> EnumSet.of(ToolCategory.READONLY);
            case CODER ->
                    EnumSet.of(ToolCategory.READONLY, ToolCategory.MUTATING, ToolCategory.EXECUTABLE, ToolCategory.INSTRUCTIONAL);
            case TESTER -> EnumSet.of(ToolCategory.READONLY, ToolCategory.EXECUTABLE, ToolCategory.INSTRUCTIONAL);
        };
    }
}

