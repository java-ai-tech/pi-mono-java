package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ReadBuiltinTool implements AgentTool {
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;
    private final int maxLines;
    private final int maxBytes;

    public ReadBuiltinTool(ExecutionBackend executionBackend, ExecutionContext executionContext, int maxLines, int maxBytes) {
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
        this.maxLines = Math.max(1, maxLines);
        this.maxBytes = Math.max(1024, maxBytes);
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String label() {
        return "read";
    }

    @Override
    public String description() {
        return "Read file contents. Supports optional offset and limit for large files.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Path to the file to read (relative or absolute)"),
                        "offset", Map.of("type", "number", "description", "Line number to start from (1-indexed)"),
                        "limit", Map.of("type", "number", "description", "Maximum number of lines to read")
                ),
                "required", List.of("path")
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
                Integer offsetParam = BuiltinToolUtils.asInt(params == null ? null : params.get("offset"));
                Integer limitParam = BuiltinToolUtils.asInt(params == null ? null : params.get("limit"));

                if (rawPath == null) {
                    return AgentToolResult.error("read.path is required");
                }
                int offset = offsetParam == null ? 1 : Math.max(1, offsetParam);

                Path absolutePath = BuiltinToolUtils.resolvePathInWorkspace(executionBackend, executionContext, rawPath, false);
                String relativePath = BuiltinToolUtils.toWorkspaceRelativePath(executionBackend, executionContext, absolutePath);
                String fileContent = executionBackend.readFile(executionContext, relativePath);

                String[] lines = fileContent.split("\\n", -1);
                int totalLines = lines.length;
                int startLine = offset - 1;
                if (startLine >= totalLines) {
                    return AgentToolResult.error("Offset " + offset + " is beyond end of file (" + totalLines + " lines total)");
                }

                int endExclusive;
                if (limitParam != null) {
                    endExclusive = Math.min(totalLines, startLine + Math.max(1, limitParam));
                } else {
                    endExclusive = totalLines;
                }

                String selected = String.join("\n", java.util.Arrays.copyOfRange(lines, startLine, endExclusive));
                BuiltinToolUtils.Truncation truncation = BuiltinToolUtils.truncateHead(selected, maxLines, maxBytes);

                String output;
                Map<String, Object> details = new HashMap<>();
                if (truncation.firstLineExceedsLimit) {
                    output = String.format(
                            "[Line %d exceeds %s limit. Use narrower offset/limit to continue.]",
                            offset,
                            BuiltinToolUtils.formatSize(maxBytes)
                    );
                    details.put("truncation", truncation.toMap(maxLines, maxBytes));
                } else {
                    output = truncation.content;
                    if (truncation.truncated) {
                        int lastShownLine = startLine + Math.max(1, truncation.outputLines);
                        int nextOffset = lastShownLine + 1;
                        output += "\n\n[Showing lines " + offset + "-" + lastShownLine + " of " + totalLines
                                + ". Use offset=" + nextOffset + " to continue.]";
                        details.put("truncation", truncation.toMap(maxLines, maxBytes));
                    } else if (limitParam != null && endExclusive < totalLines) {
                        output += "\n\n[" + (totalLines - endExclusive) + " more lines in file. Use offset="
                                + (endExclusive + 1) + " to continue.]";
                    }
                }
                if (details.isEmpty()) {
                    details = Map.of(
                            "path", relativePath,
                            "totalLines", totalLines
                    );
                } else {
                    details.put("path", relativePath);
                    details.put("totalLines", totalLines);
                }

                return new AgentToolResult(List.of(new TextContent(output, null)), details);
            } catch (Exception e) {
                return AgentToolResult.error("read failed: " + e.getMessage());
            }
        });
    }
}
