package com.glmapper.coding.sdk;

import com.glmapper.agent.core.AgentEvent;
import com.glmapper.agent.core.AgentMessage;
import com.glmapper.agent.core.QueueMode;
import com.glmapper.ai.api.Model;
import com.glmapper.coding.core.domain.CreateSessionCommand;
import com.glmapper.coding.core.domain.SessionStateSnapshot;
import com.glmapper.coding.core.domain.SessionStats;
import com.glmapper.coding.core.mongo.SessionDocument;
import com.glmapper.coding.core.mongo.SessionEntryDocument;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.glmapper.agent.core.AgentConstants.DEFAULT_NAMESPACE;

@Component
public class DelphiCodingAgentSdk {
    private final AgentSessionRuntime runtime;

    public DelphiCodingAgentSdk(AgentSessionRuntime runtime) {
        this.runtime = runtime;
    }

    public AgentSessionHandle createSession(CreateAgentSessionOptions options) {
        Objects.requireNonNull(options, "options");

        // Normalize namespace: null or blank -> "default"
        String namespace = (options.namespace() == null || options.namespace().isBlank())
                ? DEFAULT_NAMESPACE : options.namespace();

        String sessionId = runtime.createSession(new CreateSessionCommand(
                namespace,
                options.projectKey(),
                options.sessionName(),
                options.provider(),
                options.modelId(),
                options.systemPrompt()
        ));

        return new AgentSessionHandle() {
            @Override
            public String sessionId() {
                return sessionId;
            }

            @Override
            public CompletableFuture<Void> prompt(String message) {
                return runtime.prompt(sessionId, namespace, message);
            }

            @Override
            public CompletableFuture<Void> cont() {
                return runtime.cont(sessionId, namespace);
            }

            @Override
            public void steer(String message) {
                runtime.steer(sessionId, namespace, message);
            }

            @Override
            public void followUp(String message) {
                runtime.followUp(sessionId, namespace, message);
            }

            @Override
            public void abort() {
                runtime.abort(sessionId, namespace);
            }

            @Override
            public void compact(Integer keepRecentMessages) {
                runtime.compact(sessionId, namespace, keepRecentMessages);
            }

            @Override
            public void setSteeringMode(String mode) {
                runtime.setSteeringMode(sessionId, namespace, parseQueueMode(mode));
            }

            @Override
            public void setFollowUpMode(String mode) {
                runtime.setFollowUpMode(sessionId, namespace, parseQueueMode(mode));
            }

            @Override
            public void setAutoCompaction(boolean enabled) {
                runtime.setAutoCompaction(sessionId, namespace, enabled);
            }

            @Override
            public void setAutoRetry(boolean enabled) {
                runtime.setAutoRetry(sessionId, namespace, enabled);
            }

            @Override
            public SessionStats stats() {
                return runtime.sessionStats(sessionId, namespace);
            }

            @Override
            public String lastAssistantText() {
                return runtime.lastAssistantText(sessionId, namespace);
            }

            @Override
            public SessionStateSnapshot state() {
                return runtime.state(sessionId, namespace);
            }
        };
    }

    public CompletableFuture<Void> prompt(String sessionId, String namespace, String message) {
        return runtime.prompt(sessionId, namespace, message);
    }

    public CompletableFuture<Void> cont(String sessionId, String namespace) {
        return runtime.cont(sessionId, namespace);
    }

    public void steer(String sessionId, String namespace, String message) {
        runtime.steer(sessionId, namespace, message);
    }

    public void followUp(String sessionId, String namespace, String message) {
        runtime.followUp(sessionId, namespace, message);
    }

    public void abort(String sessionId, String namespace) {
        runtime.abort(sessionId, namespace);
    }

    public void compact(String sessionId, String namespace, Integer keepRecentMessages) {
        runtime.compact(sessionId, namespace, keepRecentMessages);
    }

    public void setSteeringMode(String sessionId, String namespace, String mode) {
        runtime.setSteeringMode(sessionId, namespace, parseQueueMode(mode));
    }

    public void setFollowUpMode(String sessionId, String namespace, String mode) {
        runtime.setFollowUpMode(sessionId, namespace, parseQueueMode(mode));
    }

    public void setAutoCompaction(String sessionId, String namespace, boolean enabled) {
        runtime.setAutoCompaction(sessionId, namespace, enabled);
    }

    public void setAutoRetry(String sessionId, String namespace, boolean enabled) {
        runtime.setAutoRetry(sessionId, namespace, enabled);
    }

    public void setModel(String sessionId, String namespace, String provider, String modelId) {
        runtime.setModel(sessionId, namespace, provider, modelId);
    }

    public void setThinkingLevel(String sessionId, String namespace, com.glmapper.ai.api.ThinkingLevel level) {
        runtime.setThinkingLevel(sessionId, namespace, level);
    }

    public SessionStats stats(String sessionId, String namespace) {
        return runtime.sessionStats(sessionId, namespace);
    }

    public String lastAssistantText(String sessionId, String namespace) {
        return runtime.lastAssistantText(sessionId, namespace);
    }

    public SessionStateSnapshot state(String sessionId, String namespace) {
        return runtime.state(sessionId, namespace);
    }

    public List<AgentMessage> messages(String sessionId, String namespace) {
        return runtime.messages(sessionId, namespace);
    }

    public List<SessionEntryDocument> tree(String sessionId, String namespace) {
        return runtime.tree(sessionId, namespace);
    }

    public String fork(String sessionId, String namespace, String entryId, String newSessionName) {
        return runtime.forkSession(sessionId, namespace, entryId, newSessionName);
    }

    public void navigateTree(String sessionId, String namespace, String entryId) {
        runtime.navigateTree(sessionId, namespace, entryId);
    }

    public void renameSession(String sessionId, String namespace, String name) {
        runtime.renameSession(sessionId, namespace, name);
    }

    public AutoCloseable subscribeEvents(String sessionId, String namespace, Consumer<AgentEvent> listener) {
        return runtime.subscribeEvents(sessionId, namespace, listener);
    }

    public List<SessionDocument> sessions(String namespace, String projectKey) {
        return runtime.listSessions(namespace, projectKey);
    }

    public List<Model> availableModels() {
        return runtime.availableModels();
    }

    public List<Model> availableModels(String provider) {
        return runtime.availableModels(provider);
    }

    private QueueMode parseQueueMode(String mode) {
        if (mode == null) {
            return QueueMode.ALL;
        }
        return "one-at-a-time".equalsIgnoreCase(mode) ? QueueMode.ONE_AT_A_TIME : QueueMode.ALL;
    }
}
