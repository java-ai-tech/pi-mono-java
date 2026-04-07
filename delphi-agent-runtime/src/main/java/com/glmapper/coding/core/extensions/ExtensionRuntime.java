package com.glmapper.coding.core.extensions;

import com.glmapper.agent.core.AgentEvent;
import com.glmapper.coding.core.catalog.SlashCommandInfo;
import com.glmapper.coding.core.domain.CreateSessionCommand;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExtensionRuntime {
    private final List<AgentExtension> extensions;

    public ExtensionRuntime(List<AgentExtension> extensions) {
        this.extensions = extensions == null ? List.of() : extensions;
    }

    public ExtensionDecision beforeNewSession(CreateSessionCommand command) {
        for (AgentExtension extension : extensions) {
            ExtensionDecision decision = safeDecision(() -> extension.beforeNewSession(command));
            if (decision.cancel()) {
                return decision;
            }
        }
        return ExtensionDecision.proceed();
    }

    public ExtensionDecision beforeFork(String sessionId, String entryId, String newSessionName) {
        for (AgentExtension extension : extensions) {
            ExtensionDecision decision = safeDecision(() -> extension.beforeFork(sessionId, entryId, newSessionName));
            if (decision.cancel()) {
                return decision;
            }
        }
        return ExtensionDecision.proceed();
    }

    public ExtensionDecision beforeNavigateTree(String sessionId, String entryId) {
        for (AgentExtension extension : extensions) {
            ExtensionDecision decision = safeDecision(() -> extension.beforeNavigateTree(sessionId, entryId));
            if (decision.cancel()) {
                return decision;
            }
        }
        return ExtensionDecision.proceed();
    }

    public ExtensionDecision beforeCompact(String sessionId, Integer keepRecentMessages) {
        for (AgentExtension extension : extensions) {
            ExtensionDecision decision = safeDecision(() -> extension.beforeCompact(sessionId, keepRecentMessages));
            if (decision.cancel()) {
                return decision;
            }
        }
        return ExtensionDecision.proceed();
    }

    public void onAgentEvent(String sessionId, AgentEvent event) {
        for (AgentExtension extension : extensions) {
            try {
                extension.onAgentEvent(sessionId, event);
            } catch (Exception ignored) {
            }
        }
    }

    public List<SlashCommandInfo> slashCommands() {
        List<SlashCommandInfo> commands = new ArrayList<>();
        for (AgentExtension extension : extensions) {
            try {
                commands.addAll(extension.slashCommands());
            } catch (Exception ignored) {
            }
        }
        return commands;
    }

    private ExtensionDecision safeDecision(DecisionSupplier supplier) {
        try {
            ExtensionDecision decision = supplier.get();
            return decision == null ? ExtensionDecision.proceed() : decision;
        } catch (Exception ignored) {
            return ExtensionDecision.proceed();
        }
    }

    @FunctionalInterface
    private interface DecisionSupplier {
        ExtensionDecision get();
    }
}
