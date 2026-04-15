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
import java.util.Set;
import java.util.regex.Pattern;

import static com.glmapper.agent.core.AgentConstants.DEFAULT_NAMESPACE;

@Component
public class AgentToolFactory {
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+");
    private static final Set<String> REVIEW_HINTS = Set.of("review", "审查", "评审", "检查", "code review");
    private static final Set<String> GIT_HINTS = Set.of("git", "branch", "commit", "pr", "merge", "repo", "仓库", "分支", "提交", "合并");
    private static final Set<String> PLAN_HINTS = Set.of("plan", "planning", "规划", "计划", "task", "任务");

    private final SkillsResolver skillsResolver;
    private final ExecutionBackend executionBackend;

    public AgentToolFactory(SkillsResolver skillsResolver, ExecutionBackend executionBackend) {
        this.skillsResolver = skillsResolver;
        this.executionBackend = executionBackend;
    }

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

    public List<AgentTool> createPlanningTools(String namespace, String sessionId, String taskText) {
        List<AgentTool> filtered = new ArrayList<>();
        for (AgentTool tool : createTools(namespace, sessionId, true)) {
            if (tool instanceof TaskPlanningTool) {
                filtered.add(tool);
                continue;
            }
            if (tool instanceof SkillAgentTool skillTool) {
                if (skillTool.getSkill().isExecutable()) {
                    continue;
                }
                if (isSkillRelevant(skillTool.getSkill().name(), skillTool.getSkill().description(), taskText, true)) {
                    filtered.add(tool);
                }
            }
        }
        return filtered;
    }

    public List<AgentTool> createExecutionTools(String namespace, String sessionId, String taskText) {
        List<AgentTool> filtered = new ArrayList<>();
        for (AgentTool tool : createTools(namespace, sessionId, false)) {
            if (tool instanceof SkillAgentTool skillTool
                    && isSkillRelevant(skillTool.getSkill().name(), skillTool.getSkill().description(), taskText, false)) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    public Optional<AgentTool> resolveTool(String namespace, String sessionId, String toolName, boolean includePlanningTool) {
        return createTools(namespace, sessionId, includePlanningTool).stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst();
    }

    private boolean isSkillRelevant(String name, String description, String taskText, boolean planningPhase) {
        String text = normalize(taskText);
        String normalizedName = normalize(name);

        if (normalizedName.contains("git") || normalizedName.contains("workflow")) {
            return containsHint(text, GIT_HINTS);
        }
        if (normalizedName.contains("review")) {
            return containsHint(text, REVIEW_HINTS);
        }
        if (normalizedName.contains("planning") || normalizedName.contains("task-planning") || normalizedName.contains("plan")) {
            return planningPhase || containsHint(text, PLAN_HINTS);
        }
        if (text.isBlank()) {
            return false;
        }

        for (String token : tokenize(name + " " + description)) {
            if (token.length() < 3) {
                continue;
            }
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsHint(String text, Set<String> hints) {
        for (String hint : hints) {
            if (text.contains(normalize(hint))) {
                return true;
            }
        }
        return false;
    }

    private List<String> tokenize(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(SPLIT_PATTERN.split(normalized));
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase();
    }
}
