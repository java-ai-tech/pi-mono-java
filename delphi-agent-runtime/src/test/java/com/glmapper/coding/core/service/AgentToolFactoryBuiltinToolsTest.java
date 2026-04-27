package com.glmapper.coding.core.service;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.runtime.subagent.SubagentRole;
import com.glmapper.coding.core.runtime.subagent.WorkspaceScope;
import com.glmapper.coding.core.tools.policy.ToolInventory;
import com.glmapper.coding.core.tools.policy.ToolPolicyPipeline;
import com.glmapper.coding.core.tools.policy.ToolRuntimeContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;

class AgentToolFactoryBuiltinToolsTest {

    @Test
    void shouldCreateToolsViaInventoryAndPipeline() {
        // Mock dependencies
        ToolInventory toolInventory = Mockito.mock(ToolInventory.class);
        ToolPolicyPipeline toolPolicyPipeline = Mockito.mock(ToolPolicyPipeline.class);

        // Mock inventory to return empty list (we're just testing the wiring)
        Mockito.when(toolInventory.collect(any(ToolRuntimeContext.class)))
                .thenReturn(List.of());

        // Mock pipeline to pass through tools unchanged
        Mockito.when(toolPolicyPipeline.apply(any(ToolRuntimeContext.class), any()))
                .thenReturn(List.of());

        AgentToolFactory factory = new AgentToolFactory(toolInventory, toolPolicyPipeline);

        // Test createTools with namespace and sessionId
        List<AgentTool> tools = factory.createTools("tenant-a", "s-1");

        // Verify the factory delegates to inventory and pipeline
        Mockito.verify(toolInventory).collect(any(ToolRuntimeContext.class));
        Mockito.verify(toolPolicyPipeline).apply(any(ToolRuntimeContext.class), any());

        // Tools list should be empty based on our mock setup
        assertFalse(tools == null);
    }

    @Test
    void shouldCreateToolsWithFullContext() {
        ToolInventory toolInventory = Mockito.mock(ToolInventory.class);
        ToolPolicyPipeline toolPolicyPipeline = Mockito.mock(ToolPolicyPipeline.class);

        Mockito.when(toolInventory.collect(any(ToolRuntimeContext.class)))
                .thenReturn(List.of());
        Mockito.when(toolPolicyPipeline.apply(any(ToolRuntimeContext.class), any()))
                .thenReturn(List.of());

        AgentToolFactory factory = new AgentToolFactory(toolInventory, toolPolicyPipeline);

        ToolRuntimeContext context = new ToolRuntimeContext(
                "tenant-a",
                "tenant-a",
                null,
                null,
                "s-1",
                null,
                null,
                SubagentRole.ORCHESTRATOR,
                0,
                WorkspaceScope.SESSION
        );

        List<AgentTool> tools = factory.createTools(context);

        Mockito.verify(toolInventory).collect(context);
        Mockito.verify(toolPolicyPipeline).apply(any(ToolRuntimeContext.class), any());
        assertFalse(tools == null);
    }
}
