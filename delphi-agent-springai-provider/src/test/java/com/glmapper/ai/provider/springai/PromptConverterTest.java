package com.glmapper.ai.provider.springai;

import com.glmapper.ai.api.AssistantMessage;
import com.glmapper.ai.api.Context;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.StopReason;
import com.glmapper.ai.api.TextContent;
import com.glmapper.ai.api.ToolCallContent;
import com.glmapper.ai.api.ToolResultMessage;
import com.glmapper.ai.api.Usage;
import com.glmapper.ai.api.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptConverterTest {

    private static final Model MODEL = new Model(
            "deepseek-v4-pro",
            "deepseek-v4-pro",
            "spring-ai-deepseek",
            "deepseek",
            "https://api.deepseek.com/anthropic",
            false,
            List.of("text"),
            new Model.CostModel(0, 0, 0, 0),
            1024,
            1024
    );

    @Test
    void skipsEmptyUserAndAssistantMessages() {
        Context context = new Context("", List.of(
                new UserMessage(List.of(new TextContent("", null)), 1),
                new AssistantMessage(List.of(), "api", "provider", "model", Usage.empty(), StopReason.STOP, null, null, 2),
                new UserMessage(List.of(new TextContent("hello", null)), 3)
        ), List.of());

        Prompt prompt = PromptConverter.convert(MODEL, context, null);

        assertEquals(1, prompt.getInstructions().size());
        assertEquals("hello", prompt.getInstructions().get(0).getText());
    }

    @Test
    void addsNonEmptyContentForAssistantToolCalls() {
        Context context = new Context("", List.of(
                new UserMessage(List.of(new TextContent("run it", null)), 1),
                new AssistantMessage(List.of(new ToolCallContent("call-1", "bash", Map.of("command", "pwd"), null)),
                        "api", "provider", "model", Usage.empty(), StopReason.TOOL_USE, null, null, 2)
        ), List.of());

        Prompt prompt = PromptConverter.convert(MODEL, context, null);

        assertEquals(2, prompt.getInstructions().size());
        org.springframework.ai.chat.messages.AssistantMessage assistant =
                assertInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class, prompt.getInstructions().get(1));
        assertFalse(assistant.getText().isBlank());
        assertTrue(assistant.hasToolCalls());
    }

    @Test
    void usesPlaceholderForEmptyToolResult() {
        Context context = new Context("", List.of(
                new ToolResultMessage("call-1", "bash", List.<ContentBlock>of(), Map.of(), false, 1)
        ), List.of());

        Prompt prompt = PromptConverter.convert(MODEL, context, null);

        ToolResponseMessage toolResponse = assertInstanceOf(ToolResponseMessage.class, prompt.getInstructions().get(0));
        assertEquals("(empty tool result)", toolResponse.getResponses().get(0).responseData());
    }
}
