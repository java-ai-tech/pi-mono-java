package com.glmapper.coding.core.service;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.tools.SkillAgentTool;
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

    public List<AgentTool> createTools(String namespace, String sessionId) {
        String effectiveNamespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace;
        String effectiveSessionId = sessionId == null || sessionId.isBlank()
                ? "chat-" + System.currentTimeMillis()
                : sessionId;
        ExecutionContext execCtx = new ExecutionContext(effectiveNamespace, effectiveSessionId, null);
        List<AgentTool> tools = new ArrayList<>();
        for (SkillInfo skill : skillsResolver.resolveSkills(effectiveNamespace)) {
            tools.add(new SkillAgentTool(skill, executionBackend, execCtx));
        }
        return tools;
    }

    public List<AgentTool> createPlanningTools(String namespace, String sessionId, String taskText) {
        return createTools(namespace, sessionId);
    }

    public List<AgentTool> createExecutionTools(String namespace, String sessionId, String taskText) {
        return createTools(namespace, sessionId);
    }

    public Optional<AgentTool> resolveTool(String namespace, String sessionId, String toolName, boolean includePlanningTool) {
        String effectiveNamespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace;
        String effectiveSessionId = sessionId == null || sessionId.isBlank()
                ? "chat-" + System.currentTimeMillis()
                : sessionId;
        String skillName = toolName.startsWith("skill_") ? toolName.substring(6) : toolName;
        return skillsResolver.resolveSkill(effectiveNamespace, skillName)
                .map(skill -> (AgentTool) new SkillAgentTool(skill, executionBackend,
                        new ExecutionContext(effectiveNamespace, effectiveSessionId, null)));
    }
}
