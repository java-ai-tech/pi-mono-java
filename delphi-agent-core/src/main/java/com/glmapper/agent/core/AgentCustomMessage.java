package com.glmapper.agent.core;

public record AgentCustomMessage(String role, Object payload, long timestamp) implements AgentMessage {
}
