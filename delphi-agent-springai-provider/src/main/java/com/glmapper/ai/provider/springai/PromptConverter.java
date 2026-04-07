package com.glmapper.ai.provider.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.ai.api.*;
import com.glmapper.ai.api.Message;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PromptConverter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PromptConverter() {}

    static Prompt convert(Model model, Context context, StreamOptions options) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        // System prompt
        if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(context.systemPrompt()));
        }

        // Conversation messages
        if (context.messages() != null) {
            for (Message msg : context.messages()) {
                if (msg instanceof com.glmapper.ai.api.UserMessage user) {
                    String text = extractText(user.content());
                    messages.add(new org.springframework.ai.chat.messages.UserMessage(text));
                } else if (msg instanceof com.glmapper.ai.api.AssistantMessage assistant) {
                    String text = extractText(assistant.content());
                    List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof ToolCallContent tc) {
                            toolCalls.add(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    tc.id(), "function", tc.name(), toJsonString(tc.arguments())));
                        }
                    }
                    if (toolCalls.isEmpty()) {
                        messages.add(new org.springframework.ai.chat.messages.AssistantMessage(text));
                    } else {
                        messages.add(org.springframework.ai.chat.messages.AssistantMessage.builder()
                                .content(text)
                                .toolCalls(toolCalls)
                                .build());
                    }
                } else if (msg instanceof ToolResultMessage toolResult) {
                    String resultText = extractText(toolResult.content());
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    toolResult.toolCallId(),
                                    toolResult.toolName(),
                                    resultText)))
                            .build());
                }
            }
        }

        // Build chat options with model override and parameters
        ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                .model(model.id())
                .internalToolExecutionEnabled(false);  // Agent handles tool execution

        if (options != null) {
            if (options.temperature() != null) {
                optionsBuilder.temperature(options.temperature());
            }
            if (options.maxTokens() != null) {
                optionsBuilder.maxTokens(options.maxTokens());
            }
        }

        // Tool definitions - pass as ToolCallbacks so Spring AI includes them in the request
        if (context.tools() != null && !context.tools().isEmpty()) {
            List<org.springframework.ai.tool.ToolCallback> callbacks = new ArrayList<>();
            for (com.glmapper.ai.api.ToolDefinition tool : context.tools()) {
                callbacks.add(new PassthroughToolCallback(tool.name(), tool.description(),
                        toJsonString(tool.parametersSchema())));
            }
            optionsBuilder.toolCallbacks(callbacks);
        }

        return new Prompt(messages, optionsBuilder.build());
    }

    private static String toJsonString(Map<String, Object> schema) {
        try {
            return OBJECT_MAPPER.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String extractText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextContent text) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
