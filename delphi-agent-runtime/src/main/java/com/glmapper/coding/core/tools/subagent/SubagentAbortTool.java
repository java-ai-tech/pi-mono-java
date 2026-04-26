package com.glmapper.coding.core.tools.subagent;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.runtime.subagent.SubagentRuntime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SubagentAbortTool implements AgentTool {

    private final SubagentRuntime subagentRuntime;

    public SubagentAbortTool(SubagentRuntime subagentRuntime) {
        this.subagentRuntime = subagentRuntime;
    }

    @Override
    public String name() {
        return "subagent_abort";
    }

    @Override
    public String label() {
        return "Subagent Abort";
    }

    @Override
    public String description() {
        return "Abort a running subagent.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "subagentId", Map.of("type", "string", "description", "Subagent identifier"),
                        "reason", Map.of("type", "string", "description", "Abort reason")
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
            String reason = params == null ? null : String.valueOf(params.getOrDefault("reason", "aborted_by_parent"));
            if (subagentId == null || subagentId.isBlank()) {
                return AgentToolResult.error("subagent_abort.subagentId is required");
            }
            boolean aborted = subagentRuntime.abort(subagentId, reason);
            if (!aborted) {
                return AgentToolResult.error("subagent not found: " + subagentId);
            }
            return new AgentToolResult(
                    List.of(new TextContent("aborted " + subagentId, null)),
                    Map.of("subagentId", subagentId, "aborted", true, "reason", reason)
            );
        });
    }
}

