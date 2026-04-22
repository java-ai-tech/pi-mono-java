package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class BashBuiltinTool implements AgentTool {
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;
    private final int defaultTimeoutSeconds;
    private final int maxLines;
    private final int maxBytes;

    public BashBuiltinTool(
            ExecutionBackend executionBackend,
            ExecutionContext executionContext,
            int defaultTimeoutSeconds,
            int maxLines,
            int maxBytes
    ) {
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
        this.defaultTimeoutSeconds = Math.max(1, defaultTimeoutSeconds);
        this.maxLines = Math.max(1, maxLines);
        this.maxBytes = Math.max(1024, maxBytes);
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String label() {
        return "bash";
    }

    @Override
    public String description() {
        return "Execute shell commands in the current workspace.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "Bash command to execute"),
                        "timeout", Map.of("type", "number", "description", "Timeout in seconds")
                ),
                "required", List.of("command")
        );
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String toolCallId,
            Map<String, Object> params,
            AgentToolUpdateCallback onUpdate,
            java.util.concurrent.CancellationException cancellation
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String command = BuiltinToolUtils.asString(params == null ? null : params.get("command"));
                if (command == null) {
                    return AgentToolResult.error("bash.command is required");
                }

                Integer timeoutParam = BuiltinToolUtils.asInt(params == null ? null : params.get("timeout"));
                int timeoutSeconds = timeoutParam == null ? defaultTimeoutSeconds : Math.max(1, timeoutParam);

                ExecutionOptions defaults = ExecutionOptions.defaults();
                ExecutionOptions options = new ExecutionOptions(
                        timeoutSeconds * 1000L,
                        Math.max(defaults.maxOutputBytes(), maxBytes * 4),
                        defaults.envVars()
                );

                ExecutionResult result = executionBackend.execute(executionContext, command, options);

                String output = joinOutput(result.stdout(), result.stderr());
                BuiltinToolUtils.Truncation truncation = BuiltinToolUtils.truncateTail(output, maxLines, maxBytes);

                StringBuilder text = new StringBuilder();
                if (!truncation.content.isBlank()) {
                    text.append(truncation.content);
                }

                if (result.timeout()) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append("[Command timed out after ").append(timeoutSeconds).append("s]");
                } else if (result.exitCode() != 0) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append("[Command exited with code ").append(result.exitCode()).append("]");
                }

                if (truncation.truncated) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append("[Output truncated by ").append(truncation.truncatedBy)
                            .append(" limit: ").append(BuiltinToolUtils.formatSize(maxBytes)).append("]");
                }
                if (text.isEmpty()) {
                    text.append("(no output)");
                }

                Map<String, Object> details = new HashMap<>();
                details.put("exitCode", result.exitCode());
                details.put("timeout", result.timeout());
                details.put("durationMs", result.durationMs());
                if (truncation.truncated) {
                    details.put("truncation", truncation.toMap(maxLines, maxBytes));
                }

                return new AgentToolResult(List.of(new TextContent(text.toString(), null)), details);
            } catch (Exception e) {
                return AgentToolResult.error("bash failed: " + e.getMessage());
            }
        });
    }

    private String joinOutput(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        if (out.isBlank()) {
            return err;
        }
        if (err.isBlank()) {
            return out;
        }
        return out + "\n" + err;
    }
}
