package com.glmapper.coding.core.tools.subagent;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.runtime.subagent.SubagentResult;
import com.glmapper.coding.core.runtime.subagent.SubagentRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SubagentResultTool implements AgentTool {

    private final SubagentRuntime subagentRuntime;

    public SubagentResultTool(SubagentRuntime subagentRuntime) {
        this.subagentRuntime = subagentRuntime;
    }

    @Override
    public String name() {
        return "subagent_result";
    }

    @Override
    public String label() {
        return "Subagent Result";
    }

    @Override
    public String description() {
        return "Get final result of a spawned subagent.";
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
                return AgentToolResult.error("subagent_result.subagentId is required");
            }
            return subagentRuntime.result(subagentId)
                    .<AgentToolResult>map(this::toResult)
                    .orElseGet(() -> AgentToolResult.error("subagent not found: " + subagentId));
        });
    }

    private AgentToolResult toResult(SubagentResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subagentId", result.subagentId());
        details.put("status", result.status().name().toLowerCase());
        details.put("summary", result.summary() == null ? "" : result.summary());
        details.put("error", result.errorMessage() == null ? "" : result.errorMessage());
        details.put("details", result.details() == null ? Map.of() : result.details());

        StringBuilder text = new StringBuilder();
        text.append("status=").append(result.status().name().toLowerCase());
        if (result.summary() != null && !result.summary().isBlank()) {
            text.append("\nsummary=").append(result.summary());
        }
        if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
            text.append("\nerror=").append(result.errorMessage());
        }
        return new AgentToolResult(List.of(new TextContent(text.toString(), null)), details);
    }
}

