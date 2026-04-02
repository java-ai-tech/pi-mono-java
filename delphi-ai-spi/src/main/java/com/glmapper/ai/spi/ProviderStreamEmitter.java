package com.glmapper.ai.spi;

import com.glmapper.ai.api.*;

import java.util.List;
import java.util.Map;

public final class ProviderStreamEmitter {
    private ProviderStreamEmitter() {
    }

    public static void emitSynthetic(DefaultAssistantMessageEventStream stream, AssistantMessage message) {
        stream.push(new AssistantMessageEvent.Start(message));

        List<ContentBlock> content = message.content();
        for (int i = 0; i < content.size(); i++) {
            ContentBlock block = content.get(i);
            if (block instanceof TextContent text) {
                stream.push(new AssistantMessageEvent.TextStart(i, message));
                if (text.text() != null && !text.text().isBlank()) {
                    stream.push(new AssistantMessageEvent.TextDelta(i, text.text(), message));
                }
                stream.push(new AssistantMessageEvent.TextEnd(i, text.text() == null ? "" : text.text(), message));
                continue;
            }
            if (block instanceof ThinkingContent thinking) {
                stream.push(new AssistantMessageEvent.ThinkingStart(i, message));
                if (thinking.thinking() != null && !thinking.thinking().isBlank()) {
                    stream.push(new AssistantMessageEvent.ThinkingDelta(i, thinking.thinking(), message));
                }
                stream.push(new AssistantMessageEvent.ThinkingEnd(i, thinking.thinking() == null ? "" : thinking.thinking(), message));
                continue;
            }
            if (block instanceof ToolCallContent toolCall) {
                stream.push(new AssistantMessageEvent.ToolCallStart(i, message));
                String delta = encodeToolArguments(toolCall.arguments());
                if (!delta.isBlank()) {
                    stream.push(new AssistantMessageEvent.ToolCallDelta(i, delta, message));
                }
                stream.push(new AssistantMessageEvent.ToolCallEnd(i, toolCall, message));
            }
        }

        if (message.stopReason() == StopReason.ERROR || message.stopReason() == StopReason.ABORTED) {
            stream.push(new AssistantMessageEvent.Error(message.stopReason(), message));
            return;
        }
        stream.push(new AssistantMessageEvent.Done(message.stopReason(), message));
    }

    private static String encodeToolArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        return String.valueOf(arguments);
    }
}
