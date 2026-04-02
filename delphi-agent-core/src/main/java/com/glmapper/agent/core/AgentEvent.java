package com.glmapper.agent.core;

public sealed interface AgentEvent permits AgentEvent.AgentStart,
        AgentEvent.AgentEnd,
        AgentEvent.TurnStart,
        AgentEvent.TurnEnd,
        AgentEvent.MessageStart,
        AgentEvent.MessageUpdate,
        AgentEvent.MessageEnd,
        AgentEvent.ToolExecutionStart,
        AgentEvent.ToolExecutionUpdate,
        AgentEvent.ToolExecutionEnd {

    record AgentStart() implements AgentEvent {}
    record AgentEnd(java.util.List<AgentMessage> messages) implements AgentEvent {}
    record TurnStart() implements AgentEvent {}
    record TurnEnd(AgentMessage message, java.util.List<AgentToolResultMessage> toolResults) implements AgentEvent {}
    record MessageStart(AgentMessage message) implements AgentEvent {}
    record MessageUpdate(AgentMessage message, com.glmapper.ai.api.AssistantMessageEvent assistantMessageEvent) implements AgentEvent {}
    record MessageEnd(AgentMessage message) implements AgentEvent {}
    record ToolExecutionStart(String toolCallId, String toolName, Object args) implements AgentEvent {}
    record ToolExecutionUpdate(String toolCallId, String toolName, Object args, AgentToolResult partialResult) implements AgentEvent {}
    record ToolExecutionEnd(String toolCallId, String toolName, AgentToolResult result, boolean isError) implements AgentEvent {}
}
