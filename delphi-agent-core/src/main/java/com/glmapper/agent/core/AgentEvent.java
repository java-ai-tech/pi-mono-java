package com.glmapper.agent.core;

/**
 * AgentEvent 封闭接口，定义代理生命周期中的各种事件类型。
 * 这些事件类型包括代理的开始和结束、每轮对话的开始和结束、消息的开始、更新和结束，以及工具执行的开始、更新和结束。
 * 每个事件类型都通过一个记录类来表示，记录类包含了与该事件相关的数据，例如消息内容、工具调用信息等。
 * 通过使用 AgentEvent，代理系统可以在不同的阶段触发相应的事件处理逻辑，实现对代理行为的监控、日志记录和其他相关功能。
 *
 * @author glmapper
 * @Classname AgentEvent
 */
public sealed interface AgentEvent permits
        // agent 开始
        AgentEvent.AgentStart,
        // agent 结束
        AgentEvent.AgentEnd,
        // 轮次开始
        AgentEvent.TurnStart,
        // 轮次结束
        AgentEvent.TurnEnd,
        // 消息开始
        AgentEvent.MessageStart,
        // 消息更新
        AgentEvent.MessageUpdate,
        // 消息结束
        AgentEvent.MessageEnd,
        // 工具执行开始
        AgentEvent.ToolExecutionStart,
        // 工具执行更新
        AgentEvent.ToolExecutionUpdate,
        // 工具执行结束
        AgentEvent.ToolExecutionEnd {

    record AgentStart() implements AgentEvent {
    }

    record AgentEnd(java.util.List<AgentMessage> messages) implements AgentEvent {
    }

    record TurnStart() implements AgentEvent {
    }

    record TurnEnd(AgentMessage message, java.util.List<AgentToolResultMessage> toolResults) implements AgentEvent {
    }

    record MessageStart(AgentMessage message) implements AgentEvent {
    }

    record MessageUpdate(AgentMessage message,
                         com.glmapper.ai.api.AssistantMessageEvent assistantMessageEvent) implements AgentEvent {
    }

    record MessageEnd(AgentMessage message) implements AgentEvent {
    }

    record ToolExecutionStart(String toolCallId, String toolName, Object args) implements AgentEvent {
    }

    record ToolExecutionUpdate(String toolCallId, String toolName, Object args,
                               AgentToolResult partialResult) implements AgentEvent {
    }

    record ToolExecutionEnd(String toolCallId, String toolName, AgentToolResult result,
                            boolean isError) implements AgentEvent {
    }
}
