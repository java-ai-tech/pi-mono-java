package com.glmapper.agent.core;

@FunctionalInterface
public interface AgentToolUpdateCallback {
    void onUpdate(AgentToolResult partialResult);
}
