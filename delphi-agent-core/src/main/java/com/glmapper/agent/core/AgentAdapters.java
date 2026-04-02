package com.glmapper.agent.core;

import com.glmapper.ai.api.*;

import java.util.ArrayList;
import java.util.List;

public final class AgentAdapters {
    private AgentAdapters() {
    }

    public static List<Message> defaultConvertToLlm(List<AgentMessage> messages) {
        List<Message> converted = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message instanceof AgentUserMessage user) {
                converted.add(new UserMessage(user.content(), user.timestamp()));
            } else if (message instanceof AgentAssistantMessage assistant) {
                converted.add(new AssistantMessage(
                        assistant.content(),
                        assistant.api(),
                        assistant.provider(),
                        assistant.model(),
                        assistant.usage(),
                        assistant.stopReason(),
                        assistant.errorMessage(),
                        assistant.responseId(),
                        assistant.timestamp()
                ));
            } else if (message instanceof AgentToolResultMessage toolResult) {
                converted.add(new ToolResultMessage(
                        toolResult.toolCallId(),
                        toolResult.toolName(),
                        toolResult.content(),
                        toolResult.details(),
                        toolResult.isError(),
                        toolResult.timestamp()
                ));
            }
        }
        return converted;
    }

    public static AgentAssistantMessage toAgentAssistant(AssistantMessage message) {
        return new AgentAssistantMessage(
                message.content(),
                message.api(),
                message.provider(),
                message.model(),
                message.usage(),
                message.stopReason(),
                message.errorMessage(),
                message.responseId(),
                message.timestamp()
        );
    }
}
