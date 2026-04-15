package com.glmapper.coding.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.agent.core.*;
import com.glmapper.ai.api.*;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.core.domain.CreateSessionCommand;
import com.glmapper.coding.core.domain.SessionStateSnapshot;
import com.glmapper.coding.core.domain.SessionStats;
import com.glmapper.coding.core.extensions.ExtensionCancelledException;
import com.glmapper.coding.core.extensions.ExtensionDecision;
import com.glmapper.coding.core.extensions.ExtensionRuntime;
import com.glmapper.coding.core.mongo.SessionDocument;
import com.glmapper.coding.core.mongo.SessionEntryDocument;
import com.glmapper.coding.core.mongo.SessionEntryRepository;
import com.glmapper.coding.core.mongo.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class AgentSessionRuntime {
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final SessionRepository sessionRepository;
    private final SessionEntryRepository entryRepository;
    private final AiRuntime aiRuntime;
    private final ModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;
    private final ExtensionRuntime extensionRuntime;
    private final SessionLifecycleManager lifecycleManager;
    private final SessionPromptQueue promptQueue;
    private final BranchSummarizer branchSummarizer;
    private final TokenEstimator tokenEstimator;

    private final Map<String, List<Consumer<AgentEvent>>> eventListeners = new ConcurrentHashMap<>();

    public AgentSessionRuntime(
            SessionRepository sessionRepository,
            SessionEntryRepository entryRepository,
            AiRuntime aiRuntime,
            ModelCatalog modelCatalog,
            ExtensionRuntime extensionRuntime,
            SessionLifecycleManager lifecycleManager,
            SessionPromptQueue promptQueue,
            BranchSummarizer branchSummarizer
    ) {
        this.sessionRepository = sessionRepository;
        this.entryRepository = entryRepository;
        this.aiRuntime = aiRuntime;
        this.modelCatalog = modelCatalog;
        this.extensionRuntime = extensionRuntime;
        this.lifecycleManager = lifecycleManager;
        this.promptQueue = promptQueue;
        this.branchSummarizer = branchSummarizer;
        this.tokenEstimator = new TokenEstimator();
        this.objectMapper = new ObjectMapper();

        // Set up eviction callback to persist conversation before eviction
        this.lifecycleManager.setOnEvict(managed -> {
            try {
                persistConversation(managed.sessionId, managed.agent.state().messages());
            } catch (Exception e) {
                // Log but don't propagate
            }
        });
    }

    public String createSession(CreateSessionCommand command) {
        ExtensionDecision decision = extensionRuntime.beforeNewSession(command);
        if (decision.cancel()) {
            throw new ExtensionCancelledException(decision.reason());
        }

        Model model = resolveModel(command.provider(), command.modelId());

        SessionDocument session = new SessionDocument();
        String ns = command.namespace();
        session.setNamespace(ns == null || ns.isBlank() ? "default" : ns);
        session.setProjectKey(command.projectKey());
        session.setSessionName(command.sessionName());
        session.setModelProvider(model.provider());
        session.setModelId(model.id());
        session.setThinkingLevel(ThinkingLevel.OFF.name());
        session.setSystemPrompt(command.systemPrompt() == null ? "" : command.systemPrompt());
        session.setPersistedMessageCount(0);
        session.setSteeringMode(QueueMode.ALL.name());
        session.setFollowUpMode(QueueMode.ALL.name());
        session.setAutoCompactionEnabled(false);
        session.setAutoRetryEnabled(false);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());

        SessionDocument saved = sessionRepository.save(session);
        String id = saved.getId();
        String ns2 = saved.getNamespace();
        Agent[] holder = new Agent[1];
        lifecycleManager.getOrCreate(
                id,
                ns2,
                () -> {
                    holder[0] = buildAgentFromSession(saved, List.of());
                    return holder[0];
                },
                () -> holder[0].subscribe(event -> dispatchEvent(id, event))
        );
        return id;
    }

    public CompletableFuture<Void> prompt(String sessionId, String namespace, String text) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        return promptQueue.submit(sessionId, () -> {
            Agent agent = getOrCreateLiveAgent(sessionId);
            String runId = UUID.randomUUID().toString();
            lifecycleManager.setActiveRun(sessionId, runId);
            return agent.prompt(text).whenComplete((result, error) -> {
                lifecycleManager.clearActiveRun(sessionId);
                if (error == null) {
                    persistConversation(sessionId, agent.state().messages());
                    maybeAutoCompact(sessionId, agent);
                }
            });
        });
    }

    public CompletableFuture<Void> cont(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        return promptQueue.submit(sessionId, () -> {
            Agent agent = getOrCreateLiveAgent(sessionId);
            String runId = UUID.randomUUID().toString();
            lifecycleManager.setActiveRun(sessionId, runId);
            return agent.cont().whenComplete((result, error) -> {
                lifecycleManager.clearActiveRun(sessionId);
                if (error == null) {
                    persistConversation(sessionId, agent.state().messages());
                    maybeAutoCompact(sessionId, agent);
                }
            });
        });
    }

    public void steer(String sessionId, String namespace, String text) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        Agent agent = getOrCreateLiveAgent(sessionId);
        agent.steer(new AgentUserMessage(List.of(new TextContent(text, null)), System.currentTimeMillis()));
    }

    public void followUp(String sessionId, String namespace, String text) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        Agent agent = getOrCreateLiveAgent(sessionId);
        agent.followUp(new AgentUserMessage(List.of(new TextContent(text, null)), System.currentTimeMillis()));
    }

    public void abort(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        getOrCreateLiveAgent(sessionId).abort();
        promptQueue.cancel(sessionId);
    }

    public void setModel(String sessionId, String namespace, String provider, String modelId) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Model model = resolveModel(provider, modelId);
        Agent agent = getOrCreateLiveAgent(sessionId);
        agent.state().model(model);

        session.setModelProvider(model.provider());
        session.setModelId(model.id());
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    public void setThinkingLevel(String sessionId, String namespace, ThinkingLevel thinkingLevel) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Agent agent = getOrCreateLiveAgent(sessionId);
        agent.state().thinkingLevel(thinkingLevel);

        session.setThinkingLevel(thinkingLevel.name());
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    public void setSteeringMode(String sessionId, String namespace, QueueMode mode) {
        validateNamespace(sessionId, namespace);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Agent agent = getOrCreateLiveAgent(sessionId);
        QueueMode effective = mode == null ? QueueMode.ALL : mode;
        agent.steeringMode(effective);
        session.setSteeringMode(effective.name());
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    public void setFollowUpMode(String sessionId, String namespace, QueueMode mode) {
        validateNamespace(sessionId, namespace);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Agent agent = getOrCreateLiveAgent(sessionId);
        QueueMode effective = mode == null ? QueueMode.ALL : mode;
        agent.followUpMode(effective);
        session.setFollowUpMode(effective.name());
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    public void setAutoCompaction(String sessionId, String namespace, boolean enabled) {
        validateNamespace(sessionId, namespace);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        session.setAutoCompactionEnabled(enabled);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    public void setAutoRetry(String sessionId, String namespace, boolean enabled) {
        validateNamespace(sessionId, namespace);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        session.setAutoRetryEnabled(enabled);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    public void renameSession(String sessionId, String namespace, String name) {
        validateNamespace(sessionId, namespace);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        session.setSessionName(name);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    public SessionStats sessionStats(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        Agent agent = getOrCreateLiveAgent(sessionId);
        List<AgentMessage> messages = agent.state().messages();
        int user = 0;
        int assistant = 0;
        int tool = 0;
        for (AgentMessage message : messages) {
            if (message instanceof AgentUserMessage) {
                user++;
            } else if (message instanceof AgentAssistantMessage) {
                assistant++;
            } else if (message instanceof AgentToolResultMessage) {
                tool++;
            }
        }

        int entries = entryRepository.findBySessionIdOrderByTimestampAsc(sessionId).size();
        return new SessionStats(sessionId, messages.size(), user, assistant, tool, entries, agent.state().streaming());
    }

    public String lastAssistantText(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        List<AgentMessage> messages = getOrCreateLiveAgent(sessionId).state().messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            if (message instanceof AgentAssistantMessage assistantMessage) {
                return extractText(assistantMessage.content());
            }
        }
        return "";
    }

    public AutoCloseable subscribeEvents(String sessionId, String namespace, Consumer<AgentEvent> listener) {
        validateNamespace(sessionId, namespace);
        eventListeners.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            List<Consumer<AgentEvent>> listeners = eventListeners.get(sessionId);
            if (listeners != null) {
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    eventListeners.remove(sessionId);
                }
            }
        };
    }

    public SessionStateSnapshot state(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Agent agent = getOrCreateLiveAgent(sessionId);

        return new SessionStateSnapshot(
                sessionId,
                session.getModelProvider(),
                session.getModelId(),
                agent.state().thinkingLevel().name(),
                agent.state().streaming(),
                agent.state().error(),
                agent.state().messages()
        );
    }

    public List<AgentMessage> messages(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        lifecycleManager.touch(sessionId);
        return getOrCreateLiveAgent(sessionId).state().messages();
    }

    public List<SessionEntryDocument> tree(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        return entryRepository.findBySessionIdOrderByTimestampAsc(sessionId);
    }

    public String forkSession(String sessionId, String namespace, String entryId, String newSessionName) {
        validateNamespace(sessionId, namespace);
        ExtensionDecision decision = extensionRuntime.beforeFork(sessionId, entryId, newSessionName);
        if (decision.cancel()) {
            throw new ExtensionCancelledException(decision.reason());
        }

        SessionDocument source = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        List<SessionEntryDocument> sourceEntries = entryRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        List<SessionEntryDocument> path = resolvePathEntries(sourceEntries, entryId != null ? entryId : source.getHeadEntryId());

        SessionDocument fork = new SessionDocument();
        fork.setNamespace(source.getNamespace());
        fork.setProjectKey(source.getProjectKey());
        fork.setSessionName(newSessionName == null || newSessionName.isBlank()
                ? source.getSessionName() + " (fork)"
                : newSessionName);
        fork.setModelProvider(source.getModelProvider());
        fork.setModelId(source.getModelId());
        fork.setThinkingLevel(source.getThinkingLevel());
        fork.setSystemPrompt(source.getSystemPrompt());
        fork.setPersistedMessageCount(path.size());
        fork.setSteeringMode(source.getSteeringMode());
        fork.setFollowUpMode(source.getFollowUpMode());
        fork.setAutoCompactionEnabled(source.isAutoCompactionEnabled());
        fork.setAutoRetryEnabled(source.isAutoRetryEnabled());
        fork.setCreatedAt(Instant.now());
        fork.setUpdatedAt(Instant.now());

        SessionDocument savedFork = sessionRepository.save(fork);

        String parent = null;
        String head = null;
        for (SessionEntryDocument sourceEntry : path) {
            SessionEntryDocument copy = new SessionEntryDocument();
            copy.setSessionId(savedFork.getId());
            copy.setEntryId(UUID.randomUUID().toString());
            copy.setParentId(parent);
            copy.setType(sourceEntry.getType());
            copy.setPayload(sourceEntry.getPayload());
            copy.setTimestamp(sourceEntry.getTimestamp());
            entryRepository.save(copy);
            parent = copy.getEntryId();
            head = copy.getEntryId();
        }

        savedFork.setHeadEntryId(head);
        sessionRepository.save(savedFork);

        Agent agent = buildAgentFromSession(savedFork, path);
        String forkId = savedFork.getId();
        String forkNs = savedFork.getNamespace() == null || savedFork.getNamespace().isBlank() ? "default" : savedFork.getNamespace();
        lifecycleManager.getOrCreate(
                forkId,
                forkNs,
                () -> agent,
                () -> agent.subscribe(event -> dispatchEvent(forkId, event))
        );
        return forkId;
    }

    public void navigateTree(String sessionId, String namespace, String entryId) {
        validateNamespace(sessionId, namespace);
        ExtensionDecision decision = extensionRuntime.beforeNavigateTree(sessionId, entryId);
        if (decision.cancel()) {
            throw new ExtensionCancelledException(decision.reason());
        }

        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        List<SessionEntryDocument> entries = entryRepository.findBySessionIdOrderByTimestampAsc(sessionId);

        // Get current and new paths
        List<SessionEntryDocument> currentPath = resolvePathEntries(entries, session.getHeadEntryId());
        List<SessionEntryDocument> newPath = resolvePathEntries(entries, entryId);

        // Find abandoned entries (in current path but not in new path)
        List<SessionEntryDocument> abandonedEntries = currentPath.stream()
                .filter(entry -> newPath.stream().noneMatch(e -> e.getEntryId().equals(entry.getEntryId())))
                .filter(entry -> "message".equals(entry.getType()))
                .toList();

        // Summarize abandoned branch if non-empty
        if (!abandonedEntries.isEmpty()) {
            Model model = resolveModel(session.getModelProvider(), session.getModelId());
            String summary = branchSummarizer.summarizeBranch(abandonedEntries, model, aiRuntime);
            if (summary != null && !summary.isBlank()) {
                SessionEntryDocument branchSummary = new SessionEntryDocument();
                branchSummary.setSessionId(sessionId);
                branchSummary.setEntryId(UUID.randomUUID().toString());
                branchSummary.setParentId(null);
                branchSummary.setType("branch_summary");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("summary", summary);
                payload.put("abandonedEntryCount", abandonedEntries.size());
                payload.put("timestamp", System.currentTimeMillis());
                branchSummary.setPayload(payload);
                branchSummary.setTimestamp(Instant.now());
                entryRepository.save(branchSummary);
            }
        }

        List<AgentMessage> restoredMessages = deserializeEntries(newPath);

        Agent agent = getOrCreateLiveAgent(sessionId);
        agent.state().messages(restoredMessages);

        session.setHeadEntryId(newPath.isEmpty() ? null : newPath.get(newPath.size() - 1).getEntryId());
        session.setPersistedMessageCount(restoredMessages.size());
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }
    public void compact(String sessionId, String namespace, Integer keepRecentMessages) {
        validateNamespace(sessionId, namespace);
        ExtensionDecision decision = extensionRuntime.beforeCompact(sessionId, keepRecentMessages);
        if (decision.cancel()) {
            throw new ExtensionCancelledException(decision.reason());
        }

        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Agent agent = getOrCreateLiveAgent(sessionId);
        List<AgentMessage> currentMessages = new ArrayList<>(agent.state().messages());

        int keep = keepRecentMessages == null ? 20 : Math.max(1, keepRecentMessages);
        if (currentMessages.size() <= keep) {
            return;
        }

        int splitIndex = currentMessages.size() - keep;
        List<AgentMessage> older = currentMessages.subList(0, splitIndex);
        List<AgentMessage> recent = currentMessages.subList(splitIndex, currentMessages.size());

        String summary = summarizeMessagesWithLlm(session, older);
        String strategy = "llm";
        if (summary == null || summary.isBlank()) {
            summary = summarizeMessagesHeuristic(older);
            strategy = "heuristic";
        }

        AgentUserMessage summaryMessage = new AgentUserMessage(
                List.of(new TextContent(summary, null)),
                System.currentTimeMillis()
        );

        List<AgentMessage> compacted = new ArrayList<>();
        compacted.add(summaryMessage);
        compacted.addAll(recent);
        agent.state().messages(compacted);

        String compactionEntryId = persistCompactionEntry(sessionId, older.size(), recent.size(), summary, strategy);
        persistConversation(sessionId, compacted, true, compactionEntryId);
    }


    public List<SessionDocument> listSessions(String namespace, String projectKey) {
        return sessionRepository.findByNamespaceAndProjectKeyOrderByUpdatedAtDesc(namespace, projectKey);
    }

    private void validateNamespace(String sessionId, String namespace) {
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        String sessionNs = session.getNamespace();
        if (sessionNs == null || sessionNs.isBlank()) {
            sessionNs = "default";
        }
        if (!sessionNs.equals(namespace)) {
            throw new IllegalArgumentException("Namespace mismatch: session belongs to " + sessionNs);
        }
    }

    public List<Model> availableModels() {
        return modelCatalog.getAll();
    }

    public List<Model> availableModels(String provider) {
        return new ArrayList<>(modelCatalog.getByProvider(provider));
    }

    public String createSessionFromParent(String parentSessionId, String namespace, String newSessionName) {
        validateNamespace(parentSessionId, namespace);
        SessionDocument parent = sessionRepository.findById(parentSessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + parentSessionId));
        return forkSession(parentSessionId, namespace, parent.getHeadEntryId(),
                newSessionName == null || newSessionName.isBlank() ? parent.getSessionName() + " (child)" : newSessionName);
    }

    public SessionDocument session(String sessionId, String namespace) {
        validateNamespace(sessionId, namespace);
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    private Agent getOrCreateLiveAgent(String sessionId) {
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        List<SessionEntryDocument> entries = entryRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        List<SessionEntryDocument> path = resolvePathEntries(entries, session.getHeadEntryId());
        String namespace = session.getNamespace() == null || session.getNamespace().isBlank() ? "default" : session.getNamespace();
        Agent[] holder = new Agent[1];
        return lifecycleManager.getOrCreate(
                sessionId,
                namespace,
                () -> {
                    holder[0] = buildAgentFromSession(session, path);
                    return holder[0];
                },
                () -> holder[0].subscribe(event -> dispatchEvent(sessionId, event))
        );
    }

    private Agent buildAgentFromSession(SessionDocument session, List<SessionEntryDocument> entries) {
        Model model = resolveModel(session.getModelProvider(), session.getModelId());
        Agent agent = new Agent(aiRuntime, model, AgentOptions.defaults());
        agent.state().systemPrompt(session.getSystemPrompt() == null ? "" : session.getSystemPrompt());


        QueueMode steering = parseQueueMode(session.getSteeringMode());
        QueueMode followUp = parseQueueMode(session.getFollowUpMode());
        agent.steeringMode(steering);
        agent.followUpMode(followUp);
        if (session.getThinkingLevel() != null && !session.getThinkingLevel().isBlank()) {
            try {
                agent.state().thinkingLevel(ThinkingLevel.valueOf(session.getThinkingLevel()));
            } catch (IllegalArgumentException ignored) {
                agent.state().thinkingLevel(ThinkingLevel.OFF);
            }
        }

        List<AgentMessage> restoredMessages = deserializeEntries(entries);
        agent.state().messages(restoredMessages);
        return agent;
    }

    private List<AgentMessage> deserializeEntries(List<SessionEntryDocument> entries) {
        List<AgentMessage> restoredMessages = new ArrayList<>();
        for (SessionEntryDocument entry : entries) {
            if (!"message".equals(entry.getType())) {
                continue;
            }
            AgentMessage message = deserializeMessage(entry.getPayload());
            if (message != null) {
                restoredMessages.add(message);
            }
        }
        return restoredMessages;
    }

    private void dispatchEvent(String sessionId, AgentEvent event) {
        extensionRuntime.onAgentEvent(sessionId, event);

        List<Consumer<AgentEvent>> listeners = eventListeners.get(sessionId);
        if (listeners == null) {
            return;
        }
        for (Consumer<AgentEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
            }
        }
    }

    private void persistConversation(String sessionId, List<AgentMessage> messages) {
        persistConversation(sessionId, messages, false, null);
    }

    private void persistConversation(String sessionId,
                                     List<AgentMessage> messages,
                                     boolean resetBranch,
                                     String initialParentId) {
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        int startIndex = Math.max(0, session.getPersistedMessageCount());
        if (startIndex > messages.size()) {
            startIndex = 0;
        }

        if (resetBranch) {
            startIndex = 0;
        }

        String parent;
        if (resetBranch) {
            parent = initialParentId;
        } else {
            parent = session.getHeadEntryId();
            if (parent == null) {
                List<SessionEntryDocument> existing = entryRepository.findBySessionIdOrderByTimestampAsc(sessionId);
                if (!existing.isEmpty()) {
                    parent = existing.get(existing.size() - 1).getEntryId();
                }
            }
        }

        for (int i = startIndex; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            SessionEntryDocument entry = new SessionEntryDocument();
            entry.setSessionId(sessionId);
            entry.setEntryId(UUID.randomUUID().toString());
            entry.setParentId(parent);
            entry.setType("message");
            entry.setPayload(serializeMessage(message));
            entry.setTimestamp(Instant.ofEpochMilli(message.timestamp()));
            entryRepository.save(entry);
            parent = entry.getEntryId();
        }

        session.setHeadEntryId(parent);
        session.setPersistedMessageCount(messages.size());
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
    }

    private List<SessionEntryDocument> resolvePathEntries(List<SessionEntryDocument> entries, String leafEntryId) {
        if (leafEntryId == null || leafEntryId.isBlank()) {
            return entries.stream()
                    .filter(entry -> "message".equals(entry.getType()))
                    .toList();
        }

        Map<String, SessionEntryDocument> byEntryId = new HashMap<>();
        for (SessionEntryDocument entry : entries) {
            byEntryId.put(entry.getEntryId(), entry);
        }

        List<SessionEntryDocument> path = new ArrayList<>();
        String current = leafEntryId;
        while (current != null) {
            SessionEntryDocument entry = byEntryId.get(current);
            if (entry == null) {
                throw new IllegalArgumentException("Entry not found: " + leafEntryId);
            }
            path.add(entry);
            current = entry.getParentId();
        }

        java.util.Collections.reverse(path);
        return path;
    }

    private Map<String, Object> serializeMessage(AgentMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("timestamp", message.timestamp());
        if (message instanceof AgentUserMessage user) {
            payload.put("content", serializeContent(user.content()));
            return payload;
        }
        if (message instanceof AgentAssistantMessage assistant) {
            payload.put("content", serializeContent(assistant.content()));
            payload.put("api", assistant.api());
            payload.put("provider", assistant.provider());
            payload.put("model", assistant.model());
            payload.put("usage", objectMapper.convertValue(assistant.usage(), MAP_REF));
            payload.put("stopReason", assistant.stopReason().name());
            payload.put("errorMessage", assistant.errorMessage());
            payload.put("responseId", assistant.responseId());
            return payload;
        }
        if (message instanceof AgentToolResultMessage toolResult) {
            payload.put("toolCallId", toolResult.toolCallId());
            payload.put("toolName", toolResult.toolName());
            payload.put("content", serializeContent(toolResult.content()));
            payload.put("details", toolResult.details());
            payload.put("isError", toolResult.isError());
            return payload;
        }
        if (message instanceof AgentCustomMessage custom) {
            payload.put("payload", custom.payload());
        }
        return payload;
    }

    private List<Map<String, Object>> serializeContent(List<ContentBlock> blocks) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentBlock block : blocks) {
            content.add(objectMapper.convertValue(block, MAP_REF));
        }
        return content;
    }

    @SuppressWarnings("unchecked")
    private AgentMessage deserializeMessage(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        String role = (String) payload.get("role");
        long timestamp = ((Number) payload.getOrDefault("timestamp", System.currentTimeMillis())).longValue();

        List<ContentBlock> content = deserializeContent((List<Map<String, Object>>) payload.get("content"));

        if ("user".equals(role)) {
            return new AgentUserMessage(content, timestamp);
        }
        if ("assistant".equals(role)) {
            Map<String, Object> usageMap = (Map<String, Object>) payload.get("usage");
            Usage usage = usageMap == null
                    ? Usage.empty()
                    : objectMapper.convertValue(usageMap, Usage.class);
            StopReason stopReason = StopReason.valueOf((String) payload.getOrDefault("stopReason", StopReason.STOP.name()));
            return new AgentAssistantMessage(
                    content,
                    (String) payload.get("api"),
                    (String) payload.get("provider"),
                    (String) payload.get("model"),
                    usage,
                    stopReason,
                    (String) payload.get("errorMessage"),
                    (String) payload.get("responseId"),
                    timestamp
            );
        }
        if ("toolResult".equals(role)) {
            return new AgentToolResultMessage(
                    (String) payload.get("toolCallId"),
                    (String) payload.get("toolName"),
                    content,
                    payload.get("details"),
                    (Boolean) payload.getOrDefault("isError", false),
                    timestamp
            );
        }
        return new AgentCustomMessage(role, payload.get("payload"), timestamp);
    }

    @SuppressWarnings("unchecked")
    private List<ContentBlock> deserializeContent(List<Map<String, Object>> contentMaps) {
        if (contentMaps == null) {
            return List.of();
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map<String, Object> block : contentMaps) {
            String type = (String) block.get("type");
            if ("text".equals(type)) {
                blocks.add(new TextContent((String) block.get("text"), (String) block.get("textSignature")));
            } else if ("thinking".equals(type)) {
                blocks.add(new ThinkingContent(
                        (String) block.get("thinking"),
                        (String) block.get("thinkingSignature"),
                        (Boolean) block.getOrDefault("redacted", false)
                ));
            } else if ("image".equals(type)) {
                blocks.add(new ImageContent((String) block.get("data"), (String) block.get("mimeType")));
            } else if ("toolCall".equals(type)) {
                Map<String, Object> args = (Map<String, Object>) block.getOrDefault("arguments", Map.of());
                blocks.add(new ToolCallContent(
                        (String) block.get("id"),
                        (String) block.get("name"),
                        args,
                        (String) block.get("thoughtSignature")
                ));
            }
        }
        return blocks;
    }

    private String persistCompactionEntry(String sessionId,
                                          int summarizedMessages,
                                          int keptMessages,
                                          String summary,
                                          String strategy) {
        SessionEntryDocument compaction = new SessionEntryDocument();
        compaction.setSessionId(sessionId);
        compaction.setEntryId(UUID.randomUUID().toString());
        compaction.setParentId(null);
        compaction.setType("compaction");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategy", strategy);
        payload.put("summarizedMessages", summarizedMessages);
        payload.put("keptMessages", keptMessages);
        payload.put("summary", summary);
        payload.put("timestamp", System.currentTimeMillis());
        compaction.setPayload(payload);
        compaction.setTimestamp(Instant.now());

        entryRepository.save(compaction);
        return compaction.getEntryId();
    }

    private String summarizeMessagesWithLlm(SessionDocument session, List<AgentMessage> messages) {
        try {
            Model model = resolveModel(session.getModelProvider(), session.getModelId());
            String transcript = buildCompactionTranscript(messages);
            if (transcript.isBlank()) {
                return null;
            }

            String instruction = "You are a context compaction engine. Summarize the prior conversation accurately. "
                    + "Preserve key user requirements, decisions, constraints, errors, and unfinished tasks. "
                    + "Do not invent facts. Output concise Chinese bullet points.";
            String prompt = "请压缩以下历史对话为可继续推理的上下文摘要：\n\n" + transcript;

            Context context = new Context(
                    instruction,
                    List.of(new UserMessage(List.of(new TextContent(prompt, null)), System.currentTimeMillis())),
                    List.of()
            );

            StreamOptions streamOptions = new StreamOptions(
                    0.1,
                    1200,
                    null,
                    "sse",
                    "short",
                    null,
                    60_000,
                    Map.of(),
                    Map.of("purpose", "compaction"),
                    ThinkingLevel.OFF,
                    Map.of()
            );

            AssistantMessage response = aiRuntime.completeSimple(model, context, streamOptions).join();
            String summary = extractText(response.content()).trim();
            if (summary.isBlank()) {
                return null;
            }
            return "[context-summary] LLM compacted historical context:\n" + summary;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String summarizeMessagesHeuristic(List<AgentMessage> messages) {
        int user = 0;
        int assistant = 0;
        int tool = 0;

        List<String> excerpts = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message instanceof AgentUserMessage userMessage) {
                user++;
                appendExcerpt(excerpts, "user", extractText(userMessage.content()));
            } else if (message instanceof AgentAssistantMessage assistantMessage) {
                assistant++;
                appendExcerpt(excerpts, "assistant", extractText(assistantMessage.content()));
            } else if (message instanceof AgentToolResultMessage toolResultMessage) {
                tool++;
                appendExcerpt(excerpts, "tool:" + toolResultMessage.toolName(), extractText(toolResultMessage.content()));
            } else {
                appendExcerpt(excerpts, message.role(), "(custom message)");
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("[context-summary] previous conversation was compacted to reduce context window.");
        builder.append("\n");
        builder.append("message_counts: user=").append(user)
                .append(", assistant=").append(assistant)
                .append(", toolResult=").append(tool);

        if (!excerpts.isEmpty()) {
            builder.append("\nimportant_excerpts:");
            for (String excerpt : excerpts) {
                builder.append("\n- ").append(excerpt);
            }
        }

        return builder.toString();
    }

    private String buildCompactionTranscript(List<AgentMessage> messages) {
        StringBuilder transcript = new StringBuilder();
        int maxChars = 28_000;
        for (AgentMessage message : messages) {
            String role = message.role();
            String text = switch (message) {
                case AgentUserMessage userMessage -> extractText(userMessage.content());
                case AgentAssistantMessage assistantMessage -> extractText(assistantMessage.content());
                case AgentToolResultMessage toolResultMessage -> "toolResult(" + toolResultMessage.toolName() + "): "
                        + extractText(toolResultMessage.content());
                case AgentCustomMessage customMessage -> String.valueOf(customMessage.payload());
            };

            if (text == null || text.isBlank()) {
                continue;
            }

            String normalized = text.replace('\n', ' ').trim();
            if (normalized.length() > 600) {
                normalized = normalized.substring(0, 600) + "...";
            }

            String line = role + ": " + normalized + "\n";
            if (transcript.length() + line.length() > maxChars) {
                break;
            }
            transcript.append(line);
        }
        return transcript.toString();
    }

    private void appendExcerpt(List<String> excerpts, String role, String text) {
        if (text == null || text.isBlank() || excerpts.size() >= 12) {
            return;
        }
        String normalized = text.replace('\n', ' ').trim();
        if (normalized.length() > 220) {
            normalized = normalized.substring(0, 220) + "...";
        }
        excerpts.add(role + ": " + normalized);
    }

    private String extractText(List<ContentBlock> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextContent text) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(text.text());
            } else if (block instanceof ThinkingContent thinking) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append("[thinking] ").append(thinking.thinking());
            } else if (block instanceof ToolCallContent toolCall) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append("[toolCall ").append(toolCall.name()).append("]");
            }
        }
        return builder.toString();
    }

    private void maybeAutoCompact(String sessionId, Agent agent) {
        SessionDocument session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (!session.isAutoCompactionEnabled()) {
            return;
        }

        long estimatedTokens = tokenEstimator.estimateTotal(agent.state().messages());
        int contextWindow = agent.state().model().contextWindow();
        boolean tokenOverflow = contextWindow > 0 && estimatedTokens > (long)(contextWindow * 0.8);
        boolean messageOverflow = agent.state().messages().size() >= 60;

        if (tokenOverflow || messageOverflow) {
            int keepCount = tokenOverflow ? Math.max(10, agent.state().messages().size() / 4) : 20;
            String namespace = session.getNamespace() == null || session.getNamespace().isBlank() ? "default" : session.getNamespace();
            compact(sessionId, namespace, keepCount);
        }
    }

    private QueueMode parseQueueMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return QueueMode.ALL;
        }
        try {
            return QueueMode.valueOf(mode);
        } catch (IllegalArgumentException ignored) {
            return QueueMode.ALL;
        }
    }

    private Model resolveModel(String provider, String modelId) {
        Optional<Model> model = modelCatalog.get(provider, modelId);
        if (model.isEmpty()) {
            throw new IllegalArgumentException("Model not found: " + provider + "/" + modelId);
        }
        return model.get();
    }

    /**
     * Public wrapper for persisting orchestration results.
     * Used by OrchestratedChatService in Phase 4.
     */
    public void persistOrchestrationResult(String sessionId, String namespace, List<AgentMessage> messages) {
        validateNamespace(sessionId, namespace);
        persistConversation(sessionId, messages);
    }
}
