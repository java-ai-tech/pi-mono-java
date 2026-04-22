package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class EditBuiltinTool implements AgentTool {
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;

    public EditBuiltinTool(ExecutionBackend executionBackend, ExecutionContext executionContext) {
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
    }

    private record Replacement(String oldText, String newText, int start, int end) {
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String label() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit a file using exact text replacement. Supports multiple disjoint replacements in a single call.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> replacementSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "oldText", Map.of("type", "string", "description", "Exact text to replace"),
                        "newText", Map.of("type", "string", "description", "Replacement text")
                ),
                "required", List.of("oldText", "newText")
        );

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Path to the file to edit"),
                        "edits", Map.of("type", "array", "items", replacementSchema,
                                "description", "List of exact replacements to apply"),
                        "oldText", Map.of("type", "string", "description", "Legacy single replacement old text"),
                        "newText", Map.of("type", "string", "description", "Legacy single replacement new text")
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
                if (rawPath == null) {
                    return AgentToolResult.error("edit.path is required");
                }

                List<Replacement> replacements = parseReplacements(params);
                if (replacements.isEmpty()) {
                    return AgentToolResult.error("edit.edits must contain at least one replacement");
                }

                Path absolutePath = BuiltinToolUtils.resolvePathInWorkspace(executionBackend, executionContext, rawPath, false);
                String relativePath = BuiltinToolUtils.toWorkspaceRelativePath(executionBackend, executionContext, absolutePath);
                String originalContent = executionBackend.readFile(executionContext, relativePath);

                List<Replacement> resolved = locateAndValidate(originalContent, replacements);
                resolved.sort(Comparator.comparingInt(Replacement::start));

                StringBuilder updated = new StringBuilder();
                int cursor = 0;
                for (Replacement replacement : resolved) {
                    updated.append(originalContent, cursor, replacement.start());
                    updated.append(replacement.newText());
                    cursor = replacement.end();
                }
                updated.append(originalContent.substring(cursor));

                executionBackend.writeFile(executionContext, relativePath, updated.toString());

                int firstChangedLine = 1;
                if (!resolved.isEmpty()) {
                    firstChangedLine = countLinesBefore(originalContent, resolved.get(0).start()) + 1;
                }

                String message = "Successfully edited " + rawPath + " with " + resolved.size() + " replacement(s).";
                Map<String, Object> details = new HashMap<>();
                details.put("path", relativePath);
                details.put("editsApplied", resolved.size());
                details.put("firstChangedLine", firstChangedLine);

                return new AgentToolResult(List.of(new TextContent(message, null)), details);
            } catch (Exception e) {
                return AgentToolResult.error("edit failed: " + e.getMessage());
            }
        });
    }

    private List<Replacement> parseReplacements(Map<String, Object> params) {
        List<Replacement> replacements = new ArrayList<>();
        Object edits = params == null ? null : params.get("edits");
        if (edits instanceof List<?> editList) {
            for (Object item : editList) {
                if (!(item instanceof Map<?, ?> editMap)) {
                    continue;
                }
                String oldText = BuiltinToolUtils.asString(editMap.get("oldText"));
                String newText = editMap.get("newText") == null ? null : String.valueOf(editMap.get("newText"));
                if (oldText == null || newText == null) {
                    continue;
                }
                replacements.add(new Replacement(oldText, newText, -1, -1));
            }
        }

        if (replacements.isEmpty()) {
            String oldText = BuiltinToolUtils.asString(params == null ? null : params.get("oldText"));
            String newText = params == null || params.get("newText") == null ? null : String.valueOf(params.get("newText"));
            if (oldText != null && newText != null) {
                replacements.add(new Replacement(oldText, newText, -1, -1));
            }
        }
        return replacements;
    }

    private List<Replacement> locateAndValidate(String originalContent, List<Replacement> replacements) {
        List<Replacement> located = new ArrayList<>();
        for (Replacement replacement : replacements) {
            List<Integer> matches = findAllOccurrences(originalContent, replacement.oldText());
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("oldText not found: " + summarize(replacement.oldText()));
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("oldText must be unique in file: " + summarize(replacement.oldText()));
            }
            int start = matches.get(0);
            int end = start + replacement.oldText().length();
            located.add(new Replacement(replacement.oldText(), replacement.newText(), start, end));
        }

        located.sort(Comparator.comparingInt(Replacement::start));
        int previousEnd = -1;
        for (Replacement replacement : located) {
            if (replacement.start() < previousEnd) {
                throw new IllegalArgumentException("edits contain overlapping ranges");
            }
            previousEnd = replacement.end();
        }
        return located;
    }

    private List<Integer> findAllOccurrences(String haystack, String needle) {
        List<Integer> indices = new ArrayList<>();
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int found = haystack.indexOf(needle, from);
            if (found < 0) {
                break;
            }
            indices.add(found);
            from = found + Math.max(1, needle.length());
        }
        return indices;
    }

    private int countLinesBefore(String content, int index) {
        int lines = 0;
        for (int i = 0; i < Math.min(index, content.length()); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\n", "\\n");
        return normalized.length() > 80 ? normalized.substring(0, 80) + "..." : normalized;
    }
}
