package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class WriteBuiltinTool implements AgentTool {
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;

    public WriteBuiltinTool(ExecutionBackend executionBackend, ExecutionContext executionContext) {
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String label() {
        return "write";
    }

    @Override
    public String description() {
        return "Write content to a file. Creates the file if it does not exist and overwrites it if it does.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Path to the file to write"),
                        "content", Map.of("type", "string", "description", "Content to write")
                ),
                "required", List.of("path", "content")
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
                String rawPath = BuiltinToolUtils.asString(params == null ? null : params.get("path"));
                String content = params == null || params.get("content") == null ? null : String.valueOf(params.get("content"));
                if (rawPath == null) {
                    return AgentToolResult.error("write.path is required");
                }
                if (content == null) {
                    return AgentToolResult.error("write.content is required");
                }

                Path absolutePath = BuiltinToolUtils.resolvePathInWorkspace(executionBackend, executionContext, rawPath, false);
                String relativePath = BuiltinToolUtils.toWorkspaceRelativePath(executionBackend, executionContext, absolutePath);
                executionBackend.writeFile(executionContext, relativePath, content);

                return new AgentToolResult(
                        List.of(new TextContent(
                                "Successfully wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + rawPath,
                                null
                        )),
                        Map.of("path", relativePath)
                );
            } catch (Exception e) {
                return AgentToolResult.error("write failed: " + e.getMessage());
            }
        });
    }
}
