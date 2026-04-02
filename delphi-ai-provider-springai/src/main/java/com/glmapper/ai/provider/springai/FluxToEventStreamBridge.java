package com.glmapper.ai.provider.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.ai.api.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FluxToEventStreamBridge {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private FluxToEventStreamBridge() {}

    static void bridge(Flux<ChatResponse> flux, DefaultAssistantMessageEventStream stream, Model model) {
        StreamState state = new StreamState(model);

        flux.subscribe(
                chatResponse -> handleChunk(chatResponse, stream, state),
                error -> handleError(error, stream, state),
                () -> handleComplete(stream, state)
        );
    }

    private static void handleChunk(ChatResponse response, DefaultAssistantMessageEventStream stream, StreamState state) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return;
        }

        // Emit Start on first chunk
        if (!state.started) {
            state.started = true;
            stream.push(new AssistantMessageEvent.Start(state.buildPartial(null)));
        }

        // Process generation
        Generation generation = response.getResults().get(0);

        // Extract usage if available
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            state.usage = new Usage(
                    u.getPromptTokens(), u.getCompletionTokens(),
                    0, 0, u.getTotalTokens(), Usage.Cost.empty());
        }

        // Text content
        String text = generation.getOutput() != null ? generation.getOutput().getText() : null;
        if (text != null && !text.isEmpty()) {
            if (!state.textStarted) {
                state.textStarted = true;
                stream.push(new AssistantMessageEvent.TextStart(state.textIndex, state.buildPartial(null)));
            }
            state.textBuffer.append(text);
            stream.push(new AssistantMessageEvent.TextDelta(state.textIndex, text, state.buildPartial(null)));
        }

        // Tool calls
        if (generation.getOutput() != null && generation.getOutput().getToolCalls() != null) {
            for (var toolCall : generation.getOutput().getToolCalls()) {
                String tcId = toolCall.id();
                String tcName = toolCall.name();
                String tcArgs = toolCall.arguments();

                if (tcId != null && !state.activeToolCalls.containsKey(tcId)) {
                    state.nextContentIndex++;
                    state.activeToolCalls.put(tcId, new ToolCallState(tcId, tcName, state.nextContentIndex));
                    stream.push(new AssistantMessageEvent.ToolCallStart(
                            state.nextContentIndex, state.buildPartial(null)));
                }

                ToolCallState tcs = tcId != null ? state.activeToolCalls.get(tcId) : null;
                if (tcs != null && tcArgs != null && !tcArgs.isEmpty()) {
                    tcs.argsBuffer.append(tcArgs);
                    stream.push(new AssistantMessageEvent.ToolCallDelta(
                            tcs.contentIndex, tcArgs, state.buildPartial(null)));
                }
            }
        }

        // Check finish reason
        if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
            state.finishReason = generation.getMetadata().getFinishReason();
        }
    }

    private static void handleError(Throwable error, DefaultAssistantMessageEventStream stream, StreamState state) {
        AssistantMessage errorMsg = new AssistantMessage(
                List.of(new TextContent(state.textBuffer.toString(), null)),
                state.model.api(), state.model.provider(), state.model.id(),
                state.usage, StopReason.ERROR, error.getMessage(), null,
                System.currentTimeMillis()
        );
        stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, errorMsg));
    }

    private static void handleComplete(DefaultAssistantMessageEventStream stream, StreamState state) {
        // Close text
        if (state.textStarted) {
            stream.push(new AssistantMessageEvent.TextEnd(
                    state.textIndex, state.textBuffer.toString(), state.buildPartial(null)));
        }

        // Close tool calls
        for (ToolCallState tcs : state.activeToolCalls.values()) {
            Map<String, Object> args = parseArgs(tcs.argsBuffer.toString());
            ToolCallContent toolCall = new ToolCallContent(tcs.id, tcs.name, args, null);
            stream.push(new AssistantMessageEvent.ToolCallEnd(tcs.contentIndex, toolCall, state.buildPartial(null)));
        }

        // Build final message
        StopReason stopReason = mapFinishReason(state.finishReason, !state.activeToolCalls.isEmpty());
        List<ContentBlock> content = new ArrayList<>();
        if (!state.textBuffer.isEmpty()) {
            content.add(new TextContent(state.textBuffer.toString(), null));
        }
        for (ToolCallState tcs : state.activeToolCalls.values()) {
            content.add(new ToolCallContent(tcs.id, tcs.name, parseArgs(tcs.argsBuffer.toString()), null));
        }

        AssistantMessage finalMessage = new AssistantMessage(
                content, state.model.api(), state.model.provider(), state.model.id(),
                state.usage, stopReason, null, null, System.currentTimeMillis()
        );
        stream.push(new AssistantMessageEvent.Done(stopReason, finalMessage));
    }

    private static StopReason mapFinishReason(String finishReason, boolean hasToolCalls) {
        if (hasToolCalls) return StopReason.TOOL_USE;
        if (finishReason == null) return StopReason.STOP;
        return switch (finishReason.toUpperCase()) {
            case "STOP", "END_TURN" -> StopReason.STOP;
            case "LENGTH", "MAX_TOKENS" -> StopReason.LENGTH;
            case "TOOL_CALLS", "TOOL_USE" -> StopReason.TOOL_USE;
            default -> StopReason.STOP;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(json, MAP_REF);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static final class StreamState {
        final Model model;
        boolean started;
        boolean textStarted;
        final int textIndex = 0;
        int nextContentIndex = 0;
        final StringBuilder textBuffer = new StringBuilder();
        final Map<String, ToolCallState> activeToolCalls = new LinkedHashMap<>();
        Usage usage = Usage.empty();
        String finishReason;

        StreamState(Model model) {
            this.model = model;
        }

        AssistantMessage buildPartial(StopReason reason) {
            List<ContentBlock> content = new ArrayList<>();
            if (!textBuffer.isEmpty()) {
                content.add(new TextContent(textBuffer.toString(), null));
            }
            for (ToolCallState tcs : activeToolCalls.values()) {
                content.add(new ToolCallContent(tcs.id, tcs.name, parseArgs(tcs.argsBuffer.toString()), null));
            }
            return new AssistantMessage(
                    content, model.api(), model.provider(), model.id(),
                    usage, reason != null ? reason : StopReason.STOP,
                    null, null, System.currentTimeMillis()
            );
        }
    }

    private static final class ToolCallState {
        final String id;
        final String name;
        final int contentIndex;
        final StringBuilder argsBuffer = new StringBuilder();

        ToolCallState(String id, String name, int contentIndex) {
            this.id = id;
            this.name = name;
            this.contentIndex = contentIndex;
        }
    }
}
