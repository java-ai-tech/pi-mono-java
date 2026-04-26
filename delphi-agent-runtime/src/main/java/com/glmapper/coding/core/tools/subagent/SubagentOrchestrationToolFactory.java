package com.glmapper.coding.core.tools.subagent;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.runtime.subagent.SubagentRuntime;
import com.glmapper.coding.core.tools.policy.ToolRuntimeContext;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SubagentOrchestrationToolFactory {

    private final SubagentRuntime subagentRuntime;

    public SubagentOrchestrationToolFactory(SubagentRuntime subagentRuntime) {
        this.subagentRuntime = subagentRuntime;
    }

    public List<AgentTool> createTools(ToolRuntimeContext runtimeContext) {
        return List.of(
                new SubagentSpawnTool(subagentRuntime, runtimeContext),
                new SubagentStatusTool(subagentRuntime),
                new SubagentResultTool(subagentRuntime),
                new SubagentAbortTool(subagentRuntime)
        );
    }
}

