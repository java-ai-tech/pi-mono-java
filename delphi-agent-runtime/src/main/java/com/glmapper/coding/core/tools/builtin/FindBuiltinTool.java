package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class FindBuiltinTool implements AgentTool {
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;
    private final int defaultLimit;
    private final int maxBytes;

    public FindBuiltinTool(ExecutionBackend executionBackend, ExecutionContext executionContext, int defaultLimit, int maxBytes) {
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
        this.defaultLimit = Math.max(1, defaultLimit);
        this.maxBytes = Math.max(1024, maxBytes);
    }

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String label() {
        return "find";
    }

    @Override
    public String description() {
        return "Find files by glob pattern. Returns paths relative to the search directory.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Glob pattern to match files"),
                        "path", Map.of("type", "string", "description", "Directory to search in"),
                        "limit", Map.of("type", "number", "description", "Maximum number of results")
                ),
                "required", List.of("pattern")
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
                String pattern = BuiltinToolUtils.asString(params == null ? null : params.get("pattern"));
                if (pattern == null) {
                    return AgentToolResult.error("find.pattern is required");
                }

                String rawPath = BuiltinToolUtils.asString(params == null ? null : params.get("path"));
                Integer limitParam = BuiltinToolUtils.asInt(params == null ? null : params.get("limit"));
                int limit = limitParam == null ? defaultLimit : Math.max(1, limitParam);

                Path searchPath = BuiltinToolUtils.resolvePathInWorkspace(executionBackend, executionContext, rawPath, true);
                if (!Files.exists(searchPath)) {
                    return AgentToolResult.error("Path not found: " + searchPath);
                }

                Path workspace = BuiltinToolUtils.workspaceRoot(executionBackend, executionContext);
                PathMatcher matcher = workspace.getFileSystem().getPathMatcher("glob:" + pattern);
                List<String> results = collectMatches(searchPath, matcher, workspace, limit);

                if (results.isEmpty()) {
                    return new AgentToolResult(
                            List.of(new TextContent("No files found matching pattern", null)),
                            Map.of("matches", 0)
                    );
                }

                results.sort(String.CASE_INSENSITIVE_ORDER);
                boolean limitReached = results.size() >= limit;

                String rawOutput = String.join("\n", results);
                BuiltinToolUtils.Truncation truncation = BuiltinToolUtils.truncateHead(rawOutput, Integer.MAX_VALUE, maxBytes);

                StringBuilder text = new StringBuilder(truncation.content);
                Map<String, Object> details = new HashMap<>();
                details.put("matches", results.size());

                if (limitReached) {
                    text.append("\n\n[").append(limit).append(" results limit reached]");
                    details.put("resultLimitReached", limit);
                }
                if (truncation.truncated) {
                    text.append("\n\n[Output truncated by ").append(truncation.truncatedBy)
                            .append(" limit: ").append(BuiltinToolUtils.formatSize(maxBytes)).append("]");
                    details.put("truncation", truncation.toMap(Integer.MAX_VALUE, maxBytes));
                }

                return new AgentToolResult(List.of(new TextContent(text.toString(), null)), details);
            } catch (Exception e) {
                return AgentToolResult.error("find failed: " + e.getMessage());
            }
        });
    }

    private List<String> collectMatches(Path searchPath, PathMatcher matcher, Path workspace, int limit) throws IOException {
        List<String> results = new ArrayList<>();
        if (Files.isRegularFile(searchPath)) {
            Path relative = workspace.relativize(searchPath);
            if (matches(matcher, relative)) {
                results.add(relative.toString().replace('\\', '/'));
            }
            return results;
        }

        Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.equals(".git") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (results.size() >= limit) {
                    return FileVisitResult.TERMINATE;
                }
                Path relative = workspace.relativize(file);
                if (matches(matcher, relative)) {
                    results.add(relative.toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    private boolean matches(PathMatcher matcher, Path relative) {
        String unix = relative.toString().replace('\\', '/');
        return matcher.matches(relative)
                || matcher.matches(Path.of(unix))
                || matcher.matches(relative.getFileName());
    }
}
