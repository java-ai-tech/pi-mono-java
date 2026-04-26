package com.glmapper.coding.core.tools.subagent;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.runtime.subagent.SubagentRuntime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SubagentStatusTool implements AgentTool {

    private final SubagentRuntime subagentRuntime;

    public SubagentStatusTool(SubagentRuntime subagentRuntime) {
        this.subagentRuntime = subagentRuntime;
    }

    @Override
    public String name() {
        return "subagent_status";
    }

    @Override
    public String label() {
        return "Subagent Status";
    }

    @Override
    public String description() {
        return "Get status of a spawned subagent.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "subagentId", Map.of("type", "string", "description", "Subagent identifier")
                ),
                "required", List.of("subagentId")
        );
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(String toolCallId,
                                                      Map<String, Object> params,
                                                      AgentToolUpdateCallback onUpdate,
                                                      java.util.concurrent.CancellationException cancellation) {
        return CompletableFuture.supplyAsync(() -> {
            String subagentId = params == null ? null : String.valueOf(params.get("subagentId"));
            if (subagentId == null || subagentId.isBlank()) {
                return AgentToolResult.error("subagent_status.subagentId is required");
            }
            return subagentRuntime.status(subagentId)
                    .<AgentToolResult>map(status -> new AgentToolResult(
                            List.of(new TextContent(status.name().toLowerCase(), null)),
                            Map.of("subagentId", subagentId, "status", status.name().toLowerCase())
                    ))
                    .orElseGet(() -> AgentToolResult.error("subagent not found: " + subagentId));
        });
    }
}

