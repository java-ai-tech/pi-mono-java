package com.glmapper.ai.api;

public sealed interface AssistantMessageEvent permits AssistantMessageEvent.Start,
        AssistantMessageEvent.TextStart,
        AssistantMessageEvent.TextDelta,
        AssistantMessageEvent.TextEnd,
        AssistantMessageEvent.ThinkingStart,
        AssistantMessageEvent.ThinkingDelta,
        AssistantMessageEvent.ThinkingEnd,
        AssistantMessageEvent.ToolCallStart,
        AssistantMessageEvent.ToolCallDelta,
        AssistantMessageEvent.ToolCallEnd,
        AssistantMessageEvent.Done,
        AssistantMessageEvent.Error {

    record Start(AssistantMessage partial) implements AssistantMessageEvent {}
    record TextStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {}
    record TextDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {}
    record TextEnd(int contentIndex, String content, AssistantMessage partial) implements AssistantMessageEvent {}
    record ThinkingStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {}
    record ThinkingDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {}
    record ThinkingEnd(int contentIndex, String content, AssistantMessage partial) implements AssistantMessageEvent {}
    record ToolCallStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {}
    record ToolCallDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {}
    record ToolCallEnd(int contentIndex, ToolCallContent toolCall, AssistantMessage partial) implements AssistantMessageEvent {}
    record Done(StopReason reason, AssistantMessage message) implements AssistantMessageEvent {}
    record Error(StopReason reason, AssistantMessage error) implements AssistantMessageEvent {}
}
