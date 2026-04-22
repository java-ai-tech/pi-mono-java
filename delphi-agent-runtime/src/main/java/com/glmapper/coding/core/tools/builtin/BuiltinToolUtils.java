package com.glmapper.coding.core.tools.builtin;

import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BuiltinToolUtils {
    private BuiltinToolUtils() {
    }

    static final class Truncation {
        final String content;
        final boolean truncated;
        final String truncatedBy;
        final int totalLines;
        final int totalBytes;
        final int outputLines;
        final int outputBytes;
        final boolean firstLineExceedsLimit;

        Truncation(
                String content,
                boolean truncated,
                String truncatedBy,
                int totalLines,
                int totalBytes,
                int outputLines,
                int outputBytes,
                boolean firstLineExceedsLimit
        ) {
            this.content = content;
            this.truncated = truncated;
            this.truncatedBy = truncatedBy;
            this.totalLines = totalLines;
            this.totalBytes = totalBytes;
            this.outputLines = outputLines;
            this.outputBytes = outputBytes;
            this.firstLineExceedsLimit = firstLineExceedsLimit;
        }

        Map<String, Object> toMap(int maxLines, int maxBytes) {
            return Map.of(
                    "truncated", truncated,
                    "truncatedBy", truncatedBy,
                    "totalLines", totalLines,
                    "totalBytes", totalBytes,
                    "outputLines", outputLines,
                    "outputBytes", outputBytes,
                    "firstLineExceedsLimit", firstLineExceedsLimit,
                    "maxLines", maxLines,
                    "maxBytes", maxBytes
            );
        }
    }

    static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    static Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("yes")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("no")) {
            return false;
        }
        return null;
    }

    static Path workspaceRoot(ExecutionBackend backend, ExecutionContext context) {
        return backend.getWorkspacePath(context.namespace(), context.sessionId())
                .toAbsolutePath()
                .normalize();
    }

    static Path resolvePathInWorkspace(
            ExecutionBackend backend,
            ExecutionContext context,
            String rawPath,
            boolean allowCurrentDirectory
    ) {
        String candidate = (rawPath == null || rawPath.isBlank())
                ? (allowCurrentDirectory ? "." : null)
                : rawPath.trim();
        if (candidate == null) {
            throw new IllegalArgumentException("path is required");
        }

        Path workspace = workspaceRoot(backend, context);
        Path inputPath = Paths.get(candidate);
        Path resolved = inputPath.isAbsolute()
                ? inputPath.normalize()
                : workspace.resolve(candidate).normalize();

        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException("Path escapes workspace root: " + rawPath);
        }
        return resolved;
    }

    static String toWorkspaceRelativePath(
            ExecutionBackend backend,
            ExecutionContext context,
            Path absolutePath
    ) {
        Path workspace = workspaceRoot(backend, context);
        Path relative = workspace.relativize(absolutePath.normalize());
        String normalized = relative.toString().replace('\\', '/');
        return normalized.isBlank() ? "." : normalized;
    }

    static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
    }

    static Truncation truncateHead(String content, int maxLines, int maxBytes) {
        String safeContent = content == null ? "" : content;
        String[] lines = safeContent.split("\\n", -1);
        int totalLines = lines.length;
        int totalBytes = safeContent.getBytes(StandardCharsets.UTF_8).length;

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return new Truncation(
                    safeContent,
                    false,
                    null,
                    totalLines,
                    totalBytes,
                    totalLines,
                    totalBytes,
                    false
            );
        }

        int firstLineBytes = lines.length == 0 ? 0 : lines[0].getBytes(StandardCharsets.UTF_8).length;
        if (firstLineBytes > maxBytes) {
            return new Truncation("", true, "bytes", totalLines, totalBytes, 0, 0, true);
        }

        List<String> outputLines = new ArrayList<>();
        int outputBytes = 0;
        String truncatedBy = "lines";
        for (int i = 0; i < lines.length && i < maxLines; i++) {
            String line = lines[i];
            int bytes = line.getBytes(StandardCharsets.UTF_8).length + (i > 0 ? 1 : 0);
            if (outputBytes + bytes > maxBytes) {
                truncatedBy = "bytes";
                break;
            }
            outputLines.add(line);
            outputBytes += bytes;
        }

        String outputContent = String.join("\n", outputLines);
        int finalOutputBytes = outputContent.getBytes(StandardCharsets.UTF_8).length;
        return new Truncation(
                outputContent,
                true,
                truncatedBy,
                totalLines,
                totalBytes,
                outputLines.size(),
                finalOutputBytes,
                false
        );
    }

    static Truncation truncateTail(String content, int maxLines, int maxBytes) {
        String safeContent = content == null ? "" : content;
        String[] lines = safeContent.split("\\n", -1);
        int totalLines = lines.length;
        int totalBytes = safeContent.getBytes(StandardCharsets.UTF_8).length;

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return new Truncation(
                    safeContent,
                    false,
                    null,
                    totalLines,
                    totalBytes,
                    totalLines,
                    totalBytes,
                    false
            );
        }

        List<String> outputLines = new ArrayList<>();
        int outputBytes = 0;
        String truncatedBy = "lines";

        for (int i = lines.length - 1; i >= 0 && outputLines.size() < maxLines; i--) {
            String line = lines[i];
            int bytes = line.getBytes(StandardCharsets.UTF_8).length + (outputLines.isEmpty() ? 0 : 1);
            if (outputBytes + bytes > maxBytes) {
                truncatedBy = "bytes";
                break;
            }
            outputLines.add(0, line);
            outputBytes += bytes;
        }

        String outputContent = String.join("\n", outputLines);
        int finalOutputBytes = outputContent.getBytes(StandardCharsets.UTF_8).length;
        return new Truncation(
                outputContent,
                true,
                truncatedBy,
                totalLines,
                totalBytes,
                outputLines.size(),
                finalOutputBytes,
                false
        );
    }

    static String shortenLine(String line, int maxLineLength) {
        if (line == null) {
            return "";
        }
        if (maxLineLength <= 0 || line.length() <= maxLineLength) {
            return line;
        }
        return line.substring(0, maxLineLength) + "…";
    }
}
