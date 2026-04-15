package com.glmapper.agent.core;

import java.nio.charset.StandardCharsets;

/**
 * Truncates text content by line count and byte size limits.
 * Used to keep tool outputs within context window budgets.
 */
public final class OutputTruncator {

    public static final int DEFAULT_MAX_LINES = 2000;
    public static final int DEFAULT_MAX_BYTES = 50_000;

    private OutputTruncator() {
    }

    public record TruncateResult(String content, boolean truncated, int linesRemoved, int bytesRemoved) {
    }

    /**
     * Keep the head (beginning) of content — suitable for file reads.
     */
    public static TruncateResult truncateHead(String content, int maxLines, int maxBytes) {
        if (content == null || content.isEmpty()) {
            return new TruncateResult(content == null ? "" : content, false, 0, 0);
        }
        int originalBytes = content.getBytes(StandardCharsets.UTF_8).length;
        String[] lines = content.split("\n", -1);
        int originalLineCount = lines.length;

        // Apply line limit
        int keepLines = Math.min(originalLineCount, maxLines);

        // Build result respecting byte limit
        StringBuilder sb = new StringBuilder();
        int byteCount = 0;
        int keptLines = 0;
        for (int i = 0; i < keepLines; i++) {
            String line = lines[i];
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            int separatorBytes = (i > 0) ? 1 : 0; // newline separator
            if (byteCount + lineBytes + separatorBytes > maxBytes && i > 0) {
                break;
            }
            if (i > 0) {
                sb.append('\n');
                byteCount += 1;
            }
            sb.append(line);
            byteCount += lineBytes;
            keptLines++;
        }

        String result = sb.toString();
        int resultBytes = result.getBytes(StandardCharsets.UTF_8).length;
        boolean truncated = keptLines < originalLineCount;
        int linesRemoved = originalLineCount - keptLines;
        int bytesRemoved = originalBytes - resultBytes;
        return new TruncateResult(result, truncated, linesRemoved, bytesRemoved);
    }
    /**
     * Keep the tail (end) of content — suitable for command output.
     */
    public static TruncateResult truncateTail(String content, int maxLines, int maxBytes) {
        if (content == null || content.isEmpty()) {
            return new TruncateResult(content == null ? "" : content, false, 0, 0);
        }
        int originalBytes = content.getBytes(StandardCharsets.UTF_8).length;
        String[] lines = content.split("\n", -1);
        int originalLineCount = lines.length;

        // Apply line limit — take from the end
        int keepLines = Math.min(originalLineCount, maxLines);
        int startIndex = originalLineCount - keepLines;

        // Build result from end, respecting byte limit
        StringBuilder sb = new StringBuilder();
        int byteCount = 0;
        int keptLines = 0;
        for (int i = originalLineCount - 1; i >= startIndex; i--) {
            String line = lines[i];
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            int separatorBytes = (keptLines > 0) ? 1 : 0;
            if (byteCount + lineBytes + separatorBytes > maxBytes && keptLines > 0) {
                break;
            }
            byteCount += lineBytes + separatorBytes;
            keptLines++;
        }

        // Rebuild from the correct start position
        int actualStart = originalLineCount - keptLines;
        for (int i = actualStart; i < originalLineCount; i++) {
            if (i > actualStart) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }

        String result = sb.toString();
        int resultBytes = result.getBytes(StandardCharsets.UTF_8).length;
        boolean truncated = keptLines < originalLineCount;
        int linesRemoved = originalLineCount - keptLines;
        int bytesRemoved = originalBytes - resultBytes;
        return new TruncateResult(result, truncated, linesRemoved, bytesRemoved);
    }
}
