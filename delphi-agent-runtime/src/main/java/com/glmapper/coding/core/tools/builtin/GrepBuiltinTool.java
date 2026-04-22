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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GrepBuiltinTool implements AgentTool {
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;
    private final int defaultLimit;
    private final int maxLineLength;
    private final int maxBytes;

    public GrepBuiltinTool(
            ExecutionBackend executionBackend,
            ExecutionContext executionContext,
            int defaultLimit,
            int maxLineLength,
            int maxBytes
    ) {
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
        this.defaultLimit = Math.max(1, defaultLimit);
        this.maxLineLength = Math.max(80, maxLineLength);
        this.maxBytes = Math.max(1024, maxBytes);
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String label() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search file contents by regex or literal pattern. Returns matching lines with file paths and line numbers.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Regex or literal pattern"),
                        "path", Map.of("type", "string", "description", "File or directory to search"),
                        "glob", Map.of("type", "string", "description", "Glob filter, e.g. '*.ts' or '**/*.java'"),
                        "ignoreCase", Map.of("type", "boolean", "description", "Case-insensitive search"),
                        "literal", Map.of("type", "boolean", "description", "Treat pattern as literal"),
                        "context", Map.of("type", "number", "description", "Number of context lines before and after each match"),
                        "limit", Map.of("type", "number", "description", "Maximum number of matches")
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
                String patternText = BuiltinToolUtils.asString(params == null ? null : params.get("pattern"));
                if (patternText == null) {
                    return AgentToolResult.error("grep.pattern is required");
                }

                String rawPath = BuiltinToolUtils.asString(params == null ? null : params.get("path"));
                String glob = BuiltinToolUtils.asString(params == null ? null : params.get("glob"));
                Boolean ignoreCase = BuiltinToolUtils.asBoolean(params == null ? null : params.get("ignoreCase"));
                Boolean literal = BuiltinToolUtils.asBoolean(params == null ? null : params.get("literal"));
                Integer context = BuiltinToolUtils.asInt(params == null ? null : params.get("context"));
                Integer limitParam = BuiltinToolUtils.asInt(params == null ? null : params.get("limit"));

                int ctx = context == null ? 0 : Math.max(0, context);
                int limit = limitParam == null ? defaultLimit : Math.max(1, limitParam);

                Path searchPath = BuiltinToolUtils.resolvePathInWorkspace(executionBackend, executionContext, rawPath, true);
                if (!Files.exists(searchPath)) {
                    return AgentToolResult.error("Path not found: " + searchPath);
                }

                Pattern pattern = compilePattern(patternText, ignoreCase != null && ignoreCase, literal != null && literal);
                Path workspace = BuiltinToolUtils.workspaceRoot(executionBackend, executionContext);
                PathMatcher matcher = (glob == null) ? null : workspace.getFileSystem().getPathMatcher("glob:" + glob);

                List<Path> files = collectCandidateFiles(searchPath, matcher, workspace);
                List<String> outputLines = new ArrayList<>();
                int matches = 0;
                boolean linesTruncated = false;

                outer:
                for (Path file : files) {
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file);
                    } catch (IOException ignored) {
                        continue;
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        Matcher matcherResult = pattern.matcher(lines.get(i));
                        if (!matcherResult.find()) {
                            continue;
                        }

                        matches++;
                        int start = Math.max(0, i - ctx);
                        int end = Math.min(lines.size() - 1, i + ctx);
                        String relative = workspace.relativize(file).toString().replace('\\', '/');

                        for (int lineNo = start; lineNo <= end; lineNo++) {
                            String prefix = (lineNo == i) ? ":" : "-";
                            String lineText = BuiltinToolUtils.shortenLine(lines.get(lineNo), maxLineLength);
                            if (lineText.length() >= maxLineLength) {
                                linesTruncated = true;
                            }
                            outputLines.add(relative + prefix + (lineNo + 1) + ":" + lineText);
                        }

                        if (matches >= limit) {
                            break outer;
                        }
                    }
                }

                if (outputLines.isEmpty()) {
                    return new AgentToolResult(
                            List.of(new TextContent("No matches found", null)),
                            Map.of("matches", 0)
                    );
                }

                String rawOutput = String.join("\n", outputLines);
                BuiltinToolUtils.Truncation truncation = BuiltinToolUtils.truncateHead(rawOutput, Integer.MAX_VALUE, maxBytes);
                StringBuilder text = new StringBuilder(truncation.content);

                Map<String, Object> details = new HashMap<>();
                details.put("matches", matches);
                if (matches >= limit) {
                    text.append("\n\n[").append(limit).append(" matches limit reached]");
                    details.put("matchLimitReached", limit);
                }
                if (truncation.truncated) {
                    text.append("\n\n[Output truncated by ").append(truncation.truncatedBy)
                            .append(" limit: ").append(BuiltinToolUtils.formatSize(maxBytes)).append("]");
                    details.put("truncation", truncation.toMap(Integer.MAX_VALUE, maxBytes));
                }
                if (linesTruncated) {
                    details.put("linesTruncated", true);
                }

                return new AgentToolResult(List.of(new TextContent(text.toString(), null)), details);
            } catch (PatternSyntaxException e) {
                return AgentToolResult.error("Invalid regex pattern: " + e.getMessage());
            } catch (Exception e) {
                return AgentToolResult.error("grep failed: " + e.getMessage());
            }
        });
    }

    private Pattern compilePattern(String patternText, boolean ignoreCase, boolean literal) {
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        if (literal) {
            return Pattern.compile(Pattern.quote(patternText), flags);
        }
        return Pattern.compile(patternText, flags);
    }

    private List<Path> collectCandidateFiles(Path searchPath, PathMatcher matcher, Path workspace) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(searchPath)) {
            if (matchesGlob(searchPath, matcher, workspace)) {
                files.add(searchPath);
            }
            return files;
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
                if (attrs.isRegularFile() && matchesGlob(file, matcher, workspace)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private boolean matchesGlob(Path file, PathMatcher matcher, Path workspace) {
        if (matcher == null) {
            return true;
        }
        Path relative = workspace.relativize(file);
        String unix = relative.toString().replace('\\', '/');
        // PathMatcher uses OS separators; keep both checks for compatibility.
        return matcher.matches(relative)
                || matcher.matches(Path.of(unix))
                || matcher.matches(relative.getFileName());
    }
}
