package com.glmapper.coding.core.extensions;

import com.glmapper.agent.core.AgentEvent;
import com.glmapper.coding.core.catalog.SlashCommandInfo;
import com.glmapper.coding.core.domain.CreateSessionCommand;

import java.util.List;

public interface AgentExtension {
    default String name() {
        return getClass().getSimpleName();
    }

    default ExtensionDecision beforeNewSession(CreateSessionCommand command) {
        return ExtensionDecision.proceed();
    }

    default ExtensionDecision beforeFork(String sessionId, String entryId, String newSessionName) {
        return ExtensionDecision.proceed();
    }

    default ExtensionDecision beforeNavigateTree(String sessionId, String entryId) {
        return ExtensionDecision.proceed();
    }

    default ExtensionDecision beforeCompact(String sessionId, Integer keepRecentMessages) {
        return ExtensionDecision.proceed();
    }

    default void onAgentEvent(String sessionId, AgentEvent event) {
    }

    default List<SlashCommandInfo> slashCommands() {
        return List.of();
    }
}
