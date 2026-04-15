package com.glmapper.coding.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.agent.core.AgentAssistantMessage;
import com.glmapper.agent.core.AgentCustomMessage;
import com.glmapper.agent.core.AgentMessage;
import com.glmapper.agent.core.AgentToolResultMessage;
import com.glmapper.agent.core.AgentUserMessage;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.ImageContent;
import com.glmapper.ai.api.TextContent;
import com.glmapper.ai.api.ThinkingContent;
import com.glmapper.ai.api.ToolCallContent;

import java.util.List;

/**
 * Conservative token estimator using chars/4 heuristic.
 * Not a Spring bean - used as a utility class.
 */
public class TokenEstimator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int CHARS_PER_TOKEN = 4;
    private static final int IMAGE_TOKEN_ESTIMATE = 1000;

    /**
     * Estimate tokens for a single message.
     */
    public long estimateTokens(AgentMessage message) {
        if (message instanceof AgentUserMessage userMsg) {
            return estimateContentBlocks(userMsg.content());
        } else if (message instanceof AgentAssistantMessage assistantMsg) {
            return estimateContentBlocks(assistantMsg.content());
        } else if (message instanceof AgentToolResultMessage toolResultMsg) {
            return estimateContentBlocks(toolResultMsg.content());
        } else if (message instanceof AgentCustomMessage customMsg) {
            return estimateString(customMsg.payload().toString());
        }
        return 0;
    }

    /**
     * Estimate total tokens for a list of messages.
     */
    public long estimateTotal(List<AgentMessage> messages) {
        return messages.stream()
                .mapToLong(this::estimateTokens)
                .sum();
    }

    private long estimateContentBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        return blocks.stream()
                .mapToLong(this::estimateContentBlock)
                .sum();
    }

    private long estimateContentBlock(ContentBlock block) {
        if (block instanceof TextContent text) {
            return estimateString(text.text());
        } else if (block instanceof ThinkingContent thinking) {
            return estimateString(thinking.thinking());
        } else if (block instanceof ToolCallContent toolCall) {
            long nameTokens = estimateString(toolCall.name());
            long argsTokens = estimateString(serializeArguments(toolCall.arguments()));
            return nameTokens + argsTokens;
        } else if (block instanceof ImageContent) {
            return IMAGE_TOKEN_ESTIMATE;
        }
        return 0;
    }

    private long estimateString(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }

    private String serializeArguments(Object arguments) {
        if (arguments == null) {
            return "";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments);
        } catch (Exception e) {
            return arguments.toString();
        }
    }
}
