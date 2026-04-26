package com.glmapper.coding.core.tools.subagent;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.runtime.subagent.SubagentResult;
import com.glmapper.coding.core.runtime.subagent.SubagentRole;
import com.glmapper.coding.core.runtime.subagent.SubagentRuntime;
import com.glmapper.coding.core.runtime.subagent.WorkspaceScope;
import com.glmapper.coding.core.tools.policy.ToolRuntimeContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SubagentSpawnTool implements AgentTool {

    private final SubagentRuntime subagentRuntime;
    private final ToolRuntimeContext runtimeContext;

    public SubagentSpawnTool(SubagentRuntime subagentRuntime, ToolRuntimeContext runtimeContext) {
        this.subagentRuntime = subagentRuntime;
        this.runtimeContext = runtimeContext;
    }

    @Override
    public String name() {
        return "subagent_spawn";
    }

    @Override
    public String label() {
        return "Subagent Spawn";
    }

    @Override
    public String description() {
        return "Spawn a scoped subagent for planner/coder/reviewer/tester/researcher tasks.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "role", Map.of("type", "string", "description", "planner|coder|reviewer|tester|researcher"),
                        "task", Map.of("type", "string", "description", "Subagent task description"),
                        "context", Map.of("type", "string", "description", "Optional context"),
                        "workspaceScope", Map.of("type", "string", "description", "session|project|ephemeral"),
                        "mode", Map.of("type", "string", "description", "sync|async"),
                        "maxDurationSeconds", Map.of("type", "number", "description", "Maximum runtime seconds")
                ),
                "required", List.of("role", "task")
        );
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(String toolCallId,
                                                      Map<String, Object> params,
                                                      AgentToolUpdateCallback onUpdate,
                                                      java.util.concurrent.CancellationException cancellation) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> safeParams = params == null ? Map.of() : params;
            String task = asString(safeParams.get("task"));
            if (task == null || task.isBlank()) {
                return AgentToolResult.error("subagent_spawn.task is required");
            }
            SubagentRole role = SubagentRole.fromValue(asString(safeParams.get("role")), SubagentRole.CODER);
            WorkspaceScope workspaceScope = WorkspaceScope.fromValue(asString(safeParams.get("workspaceScope")), WorkspaceScope.SESSION);
            boolean asyncMode = !"sync".equalsIgnoreCase(asString(safeParams.get("mode")));
            int maxDurationSeconds = asInt(safeParams.get("maxDurationSeconds"), 300);
            String context = asString(safeParams.get("context"));

            SubagentResult result = subagentRuntime.spawn(
                    runtimeContext.namespace(),
                    runtimeContext.sessionId(),
                    role,
                    task,
                    context,
                    workspaceScope,
                    asyncMode,
                    maxDurationSeconds
            );
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("subagentId", result.subagentId());
            details.put("status", result.status().name().toLowerCase());
            details.put("summary", result.summary() == null ? "" : result.summary());
            details.put("resultAvailable", result.status() != com.glmapper.coding.core.runtime.subagent.SubagentStatus.RUNNING);
            return new AgentToolResult(
                    List.of(new TextContent(
                            "subagentId=" + result.subagentId() + ", status=" + result.status().name().toLowerCase(),
                            null
                    )),
                    details
            );
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
