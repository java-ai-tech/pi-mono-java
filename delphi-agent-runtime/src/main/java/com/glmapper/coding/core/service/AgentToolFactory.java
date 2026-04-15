package com.glmapper.coding.core.service;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.tools.SkillAgentTool;
import com.glmapper.coding.core.tools.TaskPlanningTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.glmapper.agent.core.AgentConstants.DEFAULT_NAMESPACE;

@Component
public class AgentToolFactory {

    private final SkillsResolver skillsResolver;
    private final ExecutionBackend executionBackend;

    public AgentToolFactory(SkillsResolver skillsResolver, ExecutionBackend executionBackend) {
        this.skillsResolver = skillsResolver;
        this.executionBackend = executionBackend;
    }

    /**
     * Create all available tools for a session, including all namespace-visible skills.
     * Tool selection is delegated entirely to the LLM.
     */
    public List<AgentTool> createTools(String namespace, String sessionId, boolean includePlanningTool) {
        String effectiveNamespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace;
        String effectiveSessionId = sessionId == null || sessionId.isBlank()
                ? "chat-" + System.currentTimeMillis()
                : sessionId;
        ExecutionContext execCtx = new ExecutionContext(effectiveNamespace, effectiveSessionId, null);

        List<AgentTool> tools = new ArrayList<>();
        if (includePlanningTool) {
            tools.add(new TaskPlanningTool());
        }

        List<SkillInfo> visibleSkills = skillsResolver.resolveSkills(effectiveNamespace);
        for (SkillInfo skill : visibleSkills) {
            tools.add(new SkillAgentTool(skill, executionBackend, execCtx));
        }
        return tools;
    }

    /**
     * Create tools for the planning phase — includes TaskPlanningTool + all skills.
     * The LLM decides which tools to use during planning.
     */
    public List<AgentTool> createPlanningTools(String namespace, String sessionId, String taskText) {
        return createTools(namespace, sessionId, true);
    }

    /**
     * Create tools for the execution phase — all skills, no TaskPlanningTool.
     * The LLM decides which tools to use during execution.
     */
    public List<AgentTool> createExecutionTools(String namespace, String sessionId, String taskText) {
        return createTools(namespace, sessionId, false);
    }

    public Optional<AgentTool> resolveTool(String namespace, String sessionId, String toolName, boolean includePlanningTool) {
        return createTools(namespace, sessionId, includePlanningTool).stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst();
    }
}
