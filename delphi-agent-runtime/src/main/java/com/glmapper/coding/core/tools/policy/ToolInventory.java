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
