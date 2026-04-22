package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LsBuiltinTool implements AgentTool {
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;
    private final int defaultLimit;
    private final int maxBytes;

    public LsBuiltinTool(ExecutionBackend executionBackend, ExecutionContext executionContext, int defaultLimit, int maxBytes) {
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
        this.defaultLimit = Math.max(1, defaultLimit);
        this.maxBytes = Math.max(1024, maxBytes);
    }

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public String label() {
        return "ls";
    }

    @Override
    public String description() {
        return "List directory contents sorted alphabetically. Directories end with '/'.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Directory to list"),
                        "limit", Map.of("type", "number", "description", "Maximum number of entries")
                )
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
                Integer limitParam = BuiltinToolUtils.asInt(params == null ? null : params.get("limit"));
                int limit = limitParam == null ? defaultLimit : Math.max(1, limitParam);

                Path dir = BuiltinToolUtils.resolvePathInWorkspace(executionBackend, executionContext, rawPath, true);
                if (!Files.exists(dir)) {
                    return AgentToolResult.error("Path not found: " + dir);
                }
                if (!Files.isDirectory(dir)) {
                    return AgentToolResult.error("Not a directory: " + dir);
                }

                List<String> entries = new ArrayList<>();
                try (var stream = Files.list(dir)) {
                    stream.sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                            .forEach(path -> {
                                if (entries.size() >= limit) {
                                    return;
                                }
                                String name = path.getFileName().toString();
                                if (Files.isDirectory(path)) {
                                    name += "/";
                                }
                                entries.add(name);
                            });
                }

                if (entries.isEmpty()) {
                    return new AgentToolResult(List.of(new TextContent("(empty directory)", null)), Map.of("entries", 0));
                }

                boolean limitReached = entries.size() >= limit;
                String output = String.join("\n", entries);
                BuiltinToolUtils.Truncation truncation = BuiltinToolUtils.truncateHead(output, Integer.MAX_VALUE, maxBytes);

                StringBuilder text = new StringBuilder(truncation.content);
                Map<String, Object> details = new HashMap<>();
                details.put("entries", entries.size());
                if (limitReached) {
                    text.append("\n\n[").append(limit).append(" entries limit reached]");
                    details.put("entryLimitReached", limit);
                }
                if (truncation.truncated) {
                    text.append("\n\n[Output truncated by ").append(truncation.truncatedBy)
                            .append(" limit: ").append(BuiltinToolUtils.formatSize(maxBytes)).append("]");
                    details.put("truncation", truncation.toMap(Integer.MAX_VALUE, maxBytes));
                }
                return new AgentToolResult(List.of(new TextContent(text.toString(), null)), details);
            } catch (IOException e) {
                return AgentToolResult.error("ls failed: " + e.getMessage());
            } catch (Exception e) {
                return AgentToolResult.error("ls failed: " + e.getMessage());
            }
        });
    }
}
