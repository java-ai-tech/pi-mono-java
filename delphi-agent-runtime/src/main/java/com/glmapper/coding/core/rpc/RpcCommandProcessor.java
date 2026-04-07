package com.glmapper.coding.core.rpc;

import com.glmapper.agent.core.QueueMode;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.ThinkingLevel;
import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.domain.CreateSessionCommand;
import com.glmapper.coding.core.domain.SessionStateSnapshot;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;
import com.glmapper.coding.core.execution.ExecutionAuditLogger;
import com.glmapper.coding.core.extensions.ExtensionRuntime;
import com.glmapper.coding.core.mongo.SessionDocument;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RpcCommandProcessor {
    private final AgentSessionRuntime runtime;
    private final ResourceCatalogService resourceCatalogService;
    private final ExtensionRuntime extensionRuntime;
    private final ExecutionBackend executionBackend;
    private final ExecutionAuditLogger auditLogger;

    public RpcCommandProcessor(
            AgentSessionRuntime runtime,
            ResourceCatalogService resourceCatalogService,
            ExtensionRuntime extensionRuntime,
            ExecutionBackend executionBackend,
            ExecutionAuditLogger auditLogger
    ) {
        this.runtime = runtime;
        this.resourceCatalogService = resourceCatalogService;
        this.extensionRuntime = extensionRuntime;
        this.executionBackend = executionBackend;
        this.auditLogger = auditLogger;
    }

    public RpcCommandResponse execute(RpcCommandRequest request) {
        String command = normalize(request.getType());
        try {
            return switch (command) {
                case "prompt" -> commandPrompt(request);
                case "steer" -> commandSteer(request);
                case "follow_up" -> commandFollowUp(request);
                case "abort" -> commandAbort(request);
                case "new_session" -> commandNewSession(request);
                case "get_state" -> commandGetState(request);
                case "set_model" -> commandSetModel(request);
                case "cycle_model" -> commandCycleModel(request);
                case "get_available_models" -> RpcCommandResponse.ok(request.getId(), command,
                        Map.of("models", runtime.availableModels()));
                case "set_thinking_level" -> commandSetThinkingLevel(request);
                case "cycle_thinking_level" -> commandCycleThinking(request);
                case "set_steering_mode" -> commandSetSteeringMode(request);
                case "set_follow_up_mode" -> commandSetFollowUpMode(request);
                case "compact" -> commandCompact(request);
                case "set_auto_compaction" -> commandSetAutoCompaction(request);
                case "set_auto_retry" -> commandSetAutoRetry(request);
                case "abort_retry" -> RpcCommandResponse.ok(request.getId(), command, Map.of("aborted", true));
                case "bash" -> commandBash(request);
                case "abort_bash" -> RpcCommandResponse.ok(request.getId(), command, Map.of("aborted", true));
                case "get_session_stats" -> commandSessionStats(request);
                case "export_html" -> commandExportHtml(request);
                case "switch_session" -> commandSwitchSession(request);
                case "fork" -> commandFork(request);
                case "get_fork_messages" -> commandGetForkMessages(request);
                case "get_last_assistant_text" -> commandLastAssistantText(request);
                case "set_session_name" -> commandSetSessionName(request);
                case "get_messages" -> commandGetMessages(request);
                case "get_commands" -> RpcCommandResponse.ok(request.getId(), command,
                        resourceCatalogService.slashCommands(extensionRuntime.slashCommands()));
                default -> RpcCommandResponse.error(request.getId(), command, "Unsupported RPC command: " + command);
            };
        } catch (Exception ex) {
            return RpcCommandResponse.error(request.getId(), command, ex.getMessage());
        }
    }

    private RpcCommandResponse commandPrompt(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        SessionStateSnapshot state = runtime.state(sessionId, namespace);
        String behavior = normalize(request.getStreamingBehavior());
        if (state.streaming()) {
            if ("steer".equals(behavior)) {
                runtime.steer(sessionId, namespace, request.getMessage());
                return RpcCommandResponse.ok(request.getId(), "prompt");
            }
            if ("followup".equals(behavior) || "follow_up".equals(behavior)) {
                runtime.followUp(sessionId, namespace, request.getMessage());
                return RpcCommandResponse.ok(request.getId(), "prompt");
            }
        }

        runtime.prompt(sessionId, namespace, request.getMessage());
        return RpcCommandResponse.ok(request.getId(), "prompt");
    }

    private RpcCommandResponse commandSteer(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        runtime.steer(sessionId, namespace, request.getMessage());
        return RpcCommandResponse.ok(request.getId(), "steer");
    }

    private RpcCommandResponse commandFollowUp(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        runtime.followUp(sessionId, namespace, request.getMessage());
        return RpcCommandResponse.ok(request.getId(), "follow_up");
    }

    private RpcCommandResponse commandAbort(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        runtime.abort(sessionId, namespace);
        return RpcCommandResponse.ok(request.getId(), "abort");
    }

    private RpcCommandResponse commandNewSession(RpcCommandRequest request) {
        String newSessionId;
        if (request.getParentSession() != null && !request.getParentSession().isBlank()) {
            String namespace = requireNamespace(request);
            newSessionId = runtime.createSessionFromParent(request.getParentSession(), namespace, request.getSessionName());
        } else {
            Model model = chooseDefaultModel(request.getProvider(), request.getModelId());
            String namespace = requireNamespace(request);
            String projectKey = request.getProjectKey() == null || request.getProjectKey().isBlank()
                    ? "default"
                    : request.getProjectKey();
            String sessionName = request.getSessionName() == null || request.getSessionName().isBlank()
                    ? "session-" + Instant.now().toEpochMilli()
                    : request.getSessionName();
            newSessionId = runtime.createSession(new CreateSessionCommand(
                    namespace,
                    projectKey,
                    sessionName,
                    model.provider(),
                    model.id(),
                    request.getSystemPrompt()
            ));
        }
        return RpcCommandResponse.ok(request.getId(), "new_session", Map.of("cancelled", false, "sessionId", newSessionId));
    }

    private RpcCommandResponse commandGetState(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        SessionDocument session = runtime.session(sessionId, namespace);
        SessionStateSnapshot state = runtime.state(sessionId, namespace);

        RpcSessionState rpcState = new RpcSessionState(
                sessionId,
                session.getSessionName(),
                session.getModelProvider(),
                session.getModelId(),
                state.thinkingLevel(),
                state.streaming(),
                false,
                session.getSteeringMode() == null ? QueueMode.ALL.name() : session.getSteeringMode(),
                session.getFollowUpMode() == null ? QueueMode.ALL.name() : session.getFollowUpMode(),
                session.isAutoCompactionEnabled(),
                state.messages().size(),
                0
        );
        return RpcCommandResponse.ok(request.getId(), "get_state", rpcState);
    }

    private RpcCommandResponse commandSetModel(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        runtime.setModel(sessionId, namespace, request.getProvider(), request.getModelId());
        return RpcCommandResponse.ok(request.getId(), "set_model",
                Map.of("provider", request.getProvider(), "id", request.getModelId()));
    }

    private RpcCommandResponse commandCycleModel(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        SessionDocument session = runtime.session(sessionId, namespace);
        List<Model> models = runtime.availableModels();
        if (models.isEmpty()) {
            return RpcCommandResponse.ok(request.getId(), "cycle_model", null);
        }

        int currentIndex = -1;
        for (int i = 0; i < models.size(); i++) {
            Model model = models.get(i);
            if (model.provider().equals(session.getModelProvider()) && model.id().equals(session.getModelId())) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = (currentIndex + 1 + models.size()) % models.size();
        Model next = models.get(nextIndex);
        runtime.setModel(sessionId, namespace, next.provider(), next.id());

        return RpcCommandResponse.ok(request.getId(), "cycle_model", Map.of(
                "model", Map.of("provider", next.provider(), "id", next.id()),
                "thinkingLevel", runtime.state(sessionId, namespace).thinkingLevel(),
                "isScoped", false
        ));
    }

    private RpcCommandResponse commandSetThinkingLevel(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        ThinkingLevel level = ThinkingLevel.valueOf(request.getLevel().toUpperCase(Locale.ROOT));
        runtime.setThinkingLevel(sessionId, namespace, level);
        return RpcCommandResponse.ok(request.getId(), "set_thinking_level", Map.of("level", level.name()));
    }

    private RpcCommandResponse commandCycleThinking(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        ThinkingLevel current = ThinkingLevel.valueOf(runtime.state(sessionId, namespace).thinkingLevel());
        ThinkingLevel[] levels = ThinkingLevel.values();
        int nextIndex = (current.ordinal() + 1) % levels.length;
        ThinkingLevel next = levels[nextIndex];
        runtime.setThinkingLevel(sessionId, namespace, next);
        return RpcCommandResponse.ok(request.getId(), "cycle_thinking_level", Map.of("level", next.name()));
    }

    private RpcCommandResponse commandSetSteeringMode(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        QueueMode mode = toQueueMode(request.getMode());
        runtime.setSteeringMode(sessionId, namespace, mode);
        return RpcCommandResponse.ok(request.getId(), "set_steering_mode", Map.of("mode", request.getMode()));
    }

    private RpcCommandResponse commandSetFollowUpMode(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        QueueMode mode = toQueueMode(request.getMode());
        runtime.setFollowUpMode(sessionId, namespace, mode);
        return RpcCommandResponse.ok(request.getId(), "set_follow_up_mode", Map.of("mode", request.getMode()));
    }

    private RpcCommandResponse commandCompact(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        runtime.compact(sessionId, namespace, 20);
        return RpcCommandResponse.ok(request.getId(), "compact", Map.of("success", true));
    }

    private RpcCommandResponse commandSetAutoCompaction(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        boolean enabled = request.getEnabled() != null && request.getEnabled();
        runtime.setAutoCompaction(sessionId, namespace, enabled);
        return RpcCommandResponse.ok(request.getId(), "set_auto_compaction", Map.of("enabled", enabled));
    }

    private RpcCommandResponse commandSetAutoRetry(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        boolean enabled = request.getEnabled() != null && request.getEnabled();
        runtime.setAutoRetry(sessionId, namespace, enabled);
        return RpcCommandResponse.ok(request.getId(), "set_auto_retry", Map.of("enabled", enabled));
    }

    private RpcCommandResponse commandBash(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        // Validate session belongs to namespace before executing
        runtime.session(sessionId, namespace);

        ExecutionContext context = new ExecutionContext(namespace, sessionId, null);
        ExecutionResult executionResult = executionBackend.execute(
                context,
                request.getCommand(),
                ExecutionOptions.defaults()
        );

        auditLogger.logExecution(context, request.getCommand(), executionResult);

        Map<String, Object> result = new HashMap<>();
        result.put("stdout", executionResult.stdout());
        result.put("stderr", executionResult.stderr());
        result.put("exitCode", executionResult.exitCode());
        result.put("success", executionResult.isSuccess());
        result.put("timeout", executionResult.timeout());
        result.put("truncated", executionResult.truncated());
        result.put("durationMs", executionResult.durationMs());
        return RpcCommandResponse.ok(request.getId(), "bash", result);
    }

    private RpcCommandResponse commandSessionStats(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        return RpcCommandResponse.ok(request.getId(), "get_session_stats", runtime.sessionStats(sessionId, namespace));
    }

    private RpcCommandResponse commandExportHtml(RpcCommandRequest request) throws Exception {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        String outputPath = request.getOutputPath();
        if (outputPath == null || outputPath.isBlank()) {
            outputPath = "/tmp/pi-agent-" + sessionId + ".html";
        }

        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset=\"utf-8\"><title>Session Export</title></head><body>");
        html.append("<h1>Session ").append(sessionId).append("</h1>");
        for (var message : runtime.messages(sessionId, namespace)) {
            html.append("<h3>").append(message.role()).append("</h3>");
            html.append("<pre>").append(escapeHtml(String.valueOf(message))).append("</pre>");
        }
        html.append("</body></html>");

        Path path = Path.of(outputPath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, html.toString(), StandardCharsets.UTF_8);
        return RpcCommandResponse.ok(request.getId(), "export_html", Map.of("outputPath", path.toString()));
    }

    private RpcCommandResponse commandSwitchSession(RpcCommandRequest request) {
        String sessionId = request.getSessionPath();
        String namespace = requireNamespace(request);
        runtime.session(sessionId, namespace);
        return RpcCommandResponse.ok(request.getId(), "switch_session", Map.of("cancelled", false, "sessionId", sessionId));
    }

    private RpcCommandResponse commandFork(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        String forkId = runtime.forkSession(sessionId, namespace, request.getEntryId(), null);
        return RpcCommandResponse.ok(request.getId(), "fork", Map.of("cancelled", false, "sessionId", forkId));
    }

    private RpcCommandResponse commandGetForkMessages(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        return RpcCommandResponse.ok(request.getId(), "get_fork_messages", runtime.tree(sessionId, namespace));
    }

    private RpcCommandResponse commandLastAssistantText(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        return RpcCommandResponse.ok(request.getId(), "get_last_assistant_text",
                Map.of("text", runtime.lastAssistantText(sessionId, namespace)));
    }

    private RpcCommandResponse commandSetSessionName(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        runtime.renameSession(sessionId, namespace, request.getName());
        return RpcCommandResponse.ok(request.getId(), "set_session_name", Map.of("name", request.getName()));
    }

    private RpcCommandResponse commandGetMessages(RpcCommandRequest request) {
        String sessionId = requireSessionId(request);
        String namespace = requireNamespace(request);
        return RpcCommandResponse.ok(request.getId(), "get_messages", runtime.messages(sessionId, namespace));
    }

    private String requireSessionId(RpcCommandRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for command: " + request.getType());
        }
        validateNoPathTraversal("sessionId", sessionId);
        return sessionId;
    }

    private String requireNamespace(RpcCommandRequest request) {
        String namespace = request.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required for command: " + request.getType());
        }
        validateNoPathTraversal("namespace", namespace);
        return namespace;
    }

    private void validateNoPathTraversal(String field, String value) {
        if (value.contains("..") || value.contains("/") || value.contains("\\") || value.contains("\0")) {
            throw new IllegalArgumentException("Invalid " + field + ": path traversal characters detected");
        }
    }

    private QueueMode toQueueMode(String mode) {
        if (mode == null) {
            return QueueMode.ALL;
        }
        return "one-at-a-time".equalsIgnoreCase(mode) ? QueueMode.ONE_AT_A_TIME : QueueMode.ALL;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Model chooseDefaultModel(String provider, String modelId) {
        if (provider != null && modelId != null) {
            return runtime.availableModels(provider).stream()
                    .filter(model -> model.id().equals(modelId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Model not found: " + provider + "/" + modelId));
        }
        return runtime.availableModels().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No models available"));
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
