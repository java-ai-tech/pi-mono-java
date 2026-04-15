package com.glmapper.coding.core.service;

import com.glmapper.agent.core.AgentAssistantMessage;
import com.glmapper.agent.core.AgentCustomMessage;
import com.glmapper.agent.core.AgentMessage;
import com.glmapper.agent.core.AgentToolResultMessage;
import com.glmapper.agent.core.AgentUserMessage;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.ImageContent;
import com.glmapper.ai.api.StopReason;
import com.glmapper.ai.api.TextContent;
import com.glmapper.ai.api.ThinkingContent;
import com.glmapper.ai.api.ToolCallContent;
import com.glmapper.ai.api.Usage;
import com.glmapper.ai.api.Usage.Cost;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {
    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void shouldEstimateTextContent() {
        // 100 chars = 25 tokens (chars/4)
        String text = "a".repeat(100);
        AgentUserMessage message = new AgentUserMessage(
                List.of(new TextContent(text, null)),
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(25, tokens);
    }

    @Test
    void shouldEstimateThinkingContent() {
        // 200 chars = 50 tokens
        String thinking = "b".repeat(200);
        AgentAssistantMessage message = new AgentAssistantMessage(
                List.of(new ThinkingContent(thinking, null, false)),
                "anthropic",
                "anthropic",
                "claude-3-5-sonnet-20241022",
                Usage.empty(),
                StopReason.STOP,
                null,
                "resp-1",
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(50, tokens);
    }

    @Test
    void shouldEstimateToolCallContent() {
        // name: 4 chars = 1 token
        // arguments JSON: {"key":"value"} = 15 chars = 3 tokens
        // total = 4 tokens
        ToolCallContent toolCall = new ToolCallContent(
                "call-1",
                "test",
                Map.of("key", "value"),
                null
        );
        AgentAssistantMessage message = new AgentAssistantMessage(
                List.of(toolCall),
                "anthropic",
                "anthropic",
                "claude-3-5-sonnet-20241022",
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                "resp-2",
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertTrue(tokens >= 4); // At least name + minimal args
    }

    @Test
    void shouldEstimateImageContent() {
        ImageContent image = new ImageContent("base64data", "image/png");
        AgentUserMessage message = new AgentUserMessage(
                List.of(image),
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(1000, tokens); // Fixed estimate
    }

    @Test
    void shouldEstimateMixedContent() {
        List<ContentBlock> content = List.of(
                new TextContent("a".repeat(40), null),      // 10 tokens
                new ThinkingContent("b".repeat(80), null, false), // 20 tokens
                new ImageContent("data", "image/png")       // 1000 tokens
        );
        AgentAssistantMessage message = new AgentAssistantMessage(
                content,
                "anthropic",
                "anthropic",
                "claude-3-5-sonnet-20241022",
                Usage.empty(),
                StopReason.STOP,
                null,
                "resp-3",
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(1030, tokens);
    }

    @Test
    void shouldEstimateToolResultMessage() {
        AgentToolResultMessage message = new AgentToolResultMessage(
                "call-1",
                "test",
                List.of(new TextContent("result text with 24 chars", null)), // 6 tokens
                null,
                false,
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(6, tokens);
    }

    @Test
    void shouldEstimateCustomMessage() {
        AgentCustomMessage message = new AgentCustomMessage(
                "custom",
                "payload-string-20ch!",  // 20 chars = 5 tokens
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(5, tokens);
    }

    @Test
    void shouldEstimateTotalForMultipleMessages() {
        List<AgentMessage> messages = List.of(
                new AgentUserMessage(
                        List.of(new TextContent("a".repeat(40), null)), // 10 tokens
                        System.currentTimeMillis()
                ),
                new AgentAssistantMessage(
                        List.of(new TextContent("b".repeat(80), null)), // 20 tokens
                        "anthropic",
                        "anthropic",
                        "claude-3-5-sonnet-20241022",
                        Usage.empty(),
                        StopReason.STOP,
                        null,
                        "resp-4",
                        System.currentTimeMillis()
                )
        );
        long total = estimator.estimateTotal(messages);
        assertEquals(30, total);
    }

    @Test
    void shouldHandleEmptyContent() {
        AgentUserMessage message = new AgentUserMessage(
                List.of(),
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(0, tokens);
    }

    @Test
    void shouldHandleEmptyList() {
        long total = estimator.estimateTotal(List.of());
        assertEquals(0, total);
    }

    @Test
    void shouldHandleNullText() {
        AgentUserMessage message = new AgentUserMessage(
                List.of(new TextContent(null, null)),
                System.currentTimeMillis()
        );
        long tokens = estimator.estimateTokens(message);
        assertEquals(0, tokens);
    }
}
