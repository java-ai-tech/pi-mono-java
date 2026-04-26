package com.glmapper.coding.core.service;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.runtime.subagent.SubagentRole;
import com.glmapper.coding.core.runtime.subagent.WorkspaceScope;
import com.glmapper.coding.core.tools.policy.ToolCategory;
import com.glmapper.coding.core.tools.policy.ToolInventory;
import com.glmapper.coding.core.tools.policy.ToolPolicyPipeline;
import com.glmapper.coding.core.tools.policy.ToolRuntimeContext;
import com.glmapper.coding.core.tools.subagent.SubagentOrchestrationToolFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.glmapper.agent.core.AgentConstants.DEFAULT_NAMESPACE;

@Component
public class AgentToolFactory {

    private final ToolInventory toolInventory;
    private final ToolPolicyPipeline toolPolicyPipeline;

    @Autowired(required = false)
    private SubagentOrchestrationToolFactory subagentOrchestrationToolFactory;

    public AgentToolFactory(ToolInventory toolInventory,
                            ToolPolicyPipeline toolPolicyPipeline) {
        this.toolInventory = toolInventory;
        this.toolPolicyPipeline = toolPolicyPipeline;
    }

    public List<AgentTool> createTools(String namespace, String sessionId) {
        ToolRuntimeContext context = new ToolRuntimeContext(
                namespace,
                namespace,
                null,
                null,
                sessionId == null || sessionId.isBlank() ? "chat-" + System.currentTimeMillis() : sessionId,
                null,
                null,
                SubagentRole.ORCHESTRATOR,
                0,
                WorkspaceScope.SESSION
        );
        return createTools(context);
    }

    public List<AgentTool> createTools(ToolRuntimeContext context) {
        List<ToolInventory.Item> inventoryItems = new ArrayList<>(toolInventory.collect(context));
        if (subagentOrchestrationToolFactory != null && context.agentRole() == SubagentRole.ORCHESTRATOR) {
            for (AgentTool tool : subagentOrchestrationToolFactory.createTools(context)) {
                inventoryItems.add(new ToolInventory.Item(
                        tool,
                        ToolCategory.ORCHESTRATION,
                        "builtin",
                        tool.name()
                ));
            }
        }
        return toolPolicyPipeline.apply(context, inventoryItems);
    }

    public List<AgentTool> createPlanningTools(String namespace, String sessionId, String taskText) {
        String effectiveNamespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace;
        ToolRuntimeContext context = new ToolRuntimeContext(
                effectiveNamespace,
                effectiveNamespace,
                null,
                null,
                sessionId == null || sessionId.isBlank() ? "chat-" + System.currentTimeMillis() : sessionId,
                null,
                null,
                SubagentRole.PLANNER,
                1,
                WorkspaceScope.SESSION
        );
        return createTools(context);
    }

    public List<AgentTool> createExecutionTools(String namespace, String sessionId, String taskText) {
        String effectiveNamespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace;
        ToolRuntimeContext context = new ToolRuntimeContext(
                effectiveNamespace,
                effectiveNamespace,
                null,
                null,
                sessionId == null || sessionId.isBlank() ? "chat-" + System.currentTimeMillis() : sessionId,
                null,
                null,
                SubagentRole.CODER,
                1,
                WorkspaceScope.SESSION
        );
        return createTools(context);
    }

    public Optional<AgentTool> resolveTool(String namespace,
                                           String sessionId,
                                           String toolName,
                                           boolean includePlanningTool) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        String effectiveNamespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace;
        ToolRuntimeContext context = new ToolRuntimeContext(
                effectiveNamespace,
                effectiveNamespace,
                null,
                null,
                sessionId == null || sessionId.isBlank() ? "chat-" + System.currentTimeMillis() : sessionId,
                null,
                null,
                SubagentRole.CODER,
                1,
                WorkspaceScope.SESSION
        );
        return createTools(context).stream()
                .filter(tool -> toolName.equals(tool.name()))
                .findFirst();
    }
}
