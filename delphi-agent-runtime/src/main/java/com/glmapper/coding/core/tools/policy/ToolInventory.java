package com.glmapper.coding.core.tools.policy;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.runtime.subagent.SubagentRole;
import com.glmapper.coding.core.runtime.subagent.WorkspaceScope;
import com.glmapper.coding.core.tools.SkillAgentTool;
import com.glmapper.coding.core.tools.TaskPlanningTool;
import com.glmapper.coding.core.tools.builtin.BuiltinToolFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolInventory {

    private final BuiltinToolFactory builtinToolFactory;
    private final SkillsResolver skillsResolver;
    private final ExecutionBackend executionBackend;

    public ToolInventory(BuiltinToolFactory builtinToolFactory,
                         SkillsResolver skillsResolver,
                         ExecutionBackend executionBackend) {
        this.builtinToolFactory = builtinToolFactory;
        this.skillsResolver = skillsResolver;
        this.executionBackend = executionBackend;
    }

    /**
     * 根据工具运行时上下文收集可用工具列表，包含以下步骤：
     * 1. 根据上下文信息计算有效的会话ID，支持不同的工作空间范围
     * 2. 创建执行上下文，包含命名空间、会话ID和用户ID等信息
     * 3. 收集内置工具，并根据工具名称分类
     * 4. 收集技能工具，根据技能信息创建对应的工具实例，并根据是否可执行分类
     * 5. 如果是规划者或编排者角色，添加任务规划工具
     *
     * @param context 工具运行时上下文，包含命名空间、用户ID、会话ID、子代理角色等信息
     * @return 收集到的工具列表，每个工具包含其类别、来源和资源标识等元信息
     */
    public List<Item> collect(ToolRuntimeContext context) {
        List<Item> items = new ArrayList<>();
        String effectiveSessionId = workspaceExecutionId(context);
        ExecutionContext executionContext = new ExecutionContext(
                context.namespace(),
                effectiveSessionId,
                context.userId()
        );

        for (AgentTool builtin : builtinToolFactory.createAvailableTools(executionContext)) {
            items.add(new Item(
                    builtin,
                    categoryOfBuiltin(builtin.name()),
                    "builtin",
                    builtin.name()
            ));
        }

        for (SkillInfo skill : skillsResolver.resolveSkills(context.namespace())) {
            SkillAgentTool skillTool = new SkillAgentTool(skill, executionBackend, executionContext);
            items.add(new Item(
                    skillTool,
                    skill.isExecutable() ? ToolCategory.EXECUTABLE : ToolCategory.INSTRUCTIONAL,
                    "skill",
                    skill.path()
            ));
        }

        if (context.agentRole() == SubagentRole.PLANNER || context.agentRole() == SubagentRole.ORCHESTRATOR) {
            items.add(new Item(new TaskPlanningTool(), ToolCategory.INSTRUCTIONAL, "builtin", "task_planning"));
        }
        return items;
    }

    private String workspaceExecutionId(ToolRuntimeContext context) {
        String sessionId = context.sessionId() == null || context.sessionId().isBlank()
                ? "chat-" + System.currentTimeMillis()
                : context.sessionId();
        if (context.subagentId() == null || context.subagentId().isBlank()) {
            return sessionId;
        }
        WorkspaceScope workspaceScope = context.workspaceScope() == null ? WorkspaceScope.SESSION : context.workspaceScope();
        return switch (workspaceScope) {
            case SESSION -> sessionId;
            case EPHEMERAL -> context.subagentId();
            case PROJECT -> projectWorkspaceId(context.projectKey(), sessionId);
        };
    }

    private String projectWorkspaceId(String projectKey, String fallbackSessionId) {
        if (projectKey == null || projectKey.isBlank()) {
            return fallbackSessionId;
        }
        return "project-" + projectKey.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private ToolCategory categoryOfBuiltin(String toolName) {
        return switch (toolName) {
            case "read", "grep", "find", "ls" -> ToolCategory.READONLY;
            case "write", "edit" -> ToolCategory.MUTATING;
            case "bash" -> ToolCategory.EXECUTABLE;
            default -> ToolCategory.INSTRUCTIONAL;
        };
    }

    public record Item(
            AgentTool tool,
            ToolCategory category,
            String source,
            String resource
    ) {
    }
}
