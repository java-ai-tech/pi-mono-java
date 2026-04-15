package com.glmapper.agent.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OutputTruncatorTest {

    @Test
    void truncateHead_noTruncationNeeded() {
        String content = "line1\nline2\nline3";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateHead(content, 10, 1000);

        assertFalse(result.truncated());
        assertEquals(content, result.content());
        assertEquals(0, result.linesRemoved());
        assertEquals(0, result.bytesRemoved());
    }

    @Test
    void truncateHead_byLineLimit() {
        String content = "line1\nline2\nline3\nline4\nline5";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateHead(content, 3, 10000);

        assertTrue(result.truncated());
        assertEquals("line1\nline2\nline3", result.content());
        assertEquals(2, result.linesRemoved());
        assertTrue(result.bytesRemoved() > 0);
    }

    @Test
    void truncateHead_byByteLimit() {
        String content = "12345\n67890\nabcde";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateHead(content, 100, 12);

        assertTrue(result.truncated());
        assertEquals("12345\n67890", result.content());
        assertEquals(1, result.linesRemoved());
        int expectedBytes = content.getBytes(StandardCharsets.UTF_8).length - result.content().getBytes(StandardCharsets.UTF_8).length;
        assertEquals(expectedBytes, result.bytesRemoved());
    }

    @Test
    void truncateHead_emptyContent() {
        OutputTruncator.TruncateResult result = OutputTruncator.truncateHead("", 10, 100);

        assertFalse(result.truncated());
        assertEquals("", result.content());
        assertEquals(0, result.linesRemoved());
        assertEquals(0, result.bytesRemoved());
    }

    @Test
    void truncateHead_nullContent() {
        OutputTruncator.TruncateResult result = OutputTruncator.truncateHead(null, 10, 100);

        assertFalse(result.truncated());
        assertEquals("", result.content());
        assertEquals(0, result.linesRemoved());
        assertEquals(0, result.bytesRemoved());
    }

    @Test
    void truncateTail_noTruncationNeeded() {
        String content = "line1\nline2\nline3";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateTail(content, 10, 1000);

        assertFalse(result.truncated());
        assertEquals(content, result.content());
        assertEquals(0, result.linesRemoved());
        assertEquals(0, result.bytesRemoved());
    }

    @Test
    void truncateTail_byLineLimit() {
        String content = "line1\nline2\nline3\nline4\nline5";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateTail(content, 3, 10000);

        assertTrue(result.truncated());
        assertEquals("line3\nline4\nline5", result.content());
        assertEquals(2, result.linesRemoved());
        assertTrue(result.bytesRemoved() > 0);
    }

    @Test
    void truncateTail_byByteLimit() {
        String content = "12345\n67890\nabcde";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateTail(content, 100, 12);

        assertTrue(result.truncated());
        assertEquals("67890\nabcde", result.content());
        assertEquals(1, result.linesRemoved());
        int expectedBytes = content.getBytes(StandardCharsets.UTF_8).length - result.content().getBytes(StandardCharsets.UTF_8).length;
        assertEquals(expectedBytes, result.bytesRemoved());
    }

    @Test
    void truncateTail_emptyContent() {
        OutputTruncator.TruncateResult result = OutputTruncator.truncateTail("", 10, 100);

        assertFalse(result.truncated());
        assertEquals("", result.content());
        assertEquals(0, result.linesRemoved());
        assertEquals(0, result.bytesRemoved());
    }

    @Test
    void truncateTail_nullContent() {
        OutputTruncator.TruncateResult result = OutputTruncator.truncateTail(null, 10, 100);

        assertFalse(result.truncated());
        assertEquals("", result.content());
        assertEquals(0, result.linesRemoved());
        assertEquals(0, result.bytesRemoved());
    }

    @Test
    void truncateHead_singleLine() {
        String content = "single line without newline";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateHead(content, 1, 1000);

        assertFalse(result.truncated());
        assertEquals(content, result.content());
    }

    @Test
    void truncateTail_singleLine() {
        String content = "single line without newline";
        OutputTruncator.TruncateResult result = OutputTruncator.truncateTail(content, 1, 1000);

        assertFalse(result.truncated());
        assertEquals(content, result.content());
    }
}
