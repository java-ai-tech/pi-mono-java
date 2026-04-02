package com.glmapper.agent.core;

import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.ThinkingLevel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AgentState {
    private String systemPrompt;
    private Model model;
    private ThinkingLevel thinkingLevel;
    private List<AgentTool> tools;
    private List<AgentMessage> messages;
    private boolean streaming;
    private AgentMessage streamMessage;
    private Set<String> pendingToolCalls;
    private String error;

    public AgentState(Model model) {
        this.systemPrompt = "";
        this.model = model;
        this.thinkingLevel = ThinkingLevel.OFF;
        this.tools = new ArrayList<>();
        this.messages = new ArrayList<>();
        this.streaming = false;
        this.streamMessage = null;
        this.pendingToolCalls = new LinkedHashSet<>();
        this.error = null;
    }

    public synchronized String systemPrompt() { return systemPrompt; }
    public synchronized void systemPrompt(String value) { this.systemPrompt = value; }
    public synchronized Model model() { return model; }
    public synchronized void model(Model value) { this.model = value; }
    public synchronized ThinkingLevel thinkingLevel() { return thinkingLevel; }
    public synchronized void thinkingLevel(ThinkingLevel value) { this.thinkingLevel = value; }
    public synchronized List<AgentTool> tools() { return new ArrayList<>(tools); }
    public synchronized void tools(List<AgentTool> value) { this.tools = new ArrayList<>(value); }
    public synchronized List<AgentMessage> messages() { return new ArrayList<>(messages); }
    public synchronized void messages(List<AgentMessage> value) { this.messages = new ArrayList<>(value); }
    public synchronized void appendMessage(AgentMessage message) { this.messages.add(message); }
    public synchronized boolean streaming() { return streaming; }
    public synchronized void streaming(boolean value) { this.streaming = value; }
    public synchronized AgentMessage streamMessage() { return streamMessage; }
    public synchronized void streamMessage(AgentMessage value) { this.streamMessage = value; }
    public synchronized Set<String> pendingToolCalls() { return new LinkedHashSet<>(pendingToolCalls); }
    public synchronized void pendingToolCalls(Set<String> value) { this.pendingToolCalls = new LinkedHashSet<>(value); }
    public synchronized String error() { return error; }
    public synchronized void error(String value) { this.error = value; }
}
