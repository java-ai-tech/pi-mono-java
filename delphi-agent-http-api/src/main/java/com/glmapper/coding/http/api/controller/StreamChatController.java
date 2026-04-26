package com.glmapper.coding.http.api.controller;

import com.glmapper.agent.core.NamespaceValidator;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.ThinkingLevel;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.core.domain.CreateSessionCommand;
import com.glmapper.coding.core.runtime.AgentRunRequest;
import com.glmapper.coding.core.runtime.AgentRunRuntime;
import com.glmapper.coding.core.runtime.RunQueueMode;
import com.glmapper.coding.core.runtime.RuntimeEvent;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import com.glmapper.coding.http.api.dto.ChatStreamRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class StreamChatController {

    private final AgentSessionRuntime sessionRuntime;
    private final AgentRunRuntime agentRunRuntime;
    private final ModelCatalog modelCatalog;
    private final RuntimeIdentityResolver identityResolver;

    public StreamChatController(AgentSessionRuntime sessionRuntime,
                                AgentRunRuntime agentRunRuntime,
                                ModelCatalog modelCatalog,
                                RuntimeIdentityResolver identityResolver) {
        this.sessionRuntime = sessionRuntime;
        this.agentRunRuntime = agentRunRuntime;
        this.modelCatalog = modelCatalog;
        this.identityResolver = identityResolver;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @Valid @RequestBody ChatStreamRequest request) {
        NamespaceValidator.validate(request.namespace());
        RuntimeIdentity identity = identityResolver.resolve(tenantId, userId, request);
        String sessionId = ensureSessionId(request, identity.namespace());

        if (request.command() != null) {
            return executeCommand(request, sessionId, identity);
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        AgentRunRequest runRequest = new AgentRunRequest(
                identity.tenantId(),
                identity.namespace(),
                identity.userId(),
                request.projectKey(),
                sessionId,
                request.prompt(),
                request.provider(),
                request.modelId(),
                request.systemPrompt(),
                parseQueueMode(request.queueMode()),
                Map.of(
                        "queueMode", request.queueMode() == null ? "" : request.queueMode()
                )
        );

        agentRunRuntime.stream(runRequest, event -> sendRuntimeEvent(emitter, event));

        emitter.onError(error -> emitter.completeWithError(error));
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    private SseEmitter executeCommand(ChatStreamRequest request, String sessionId, RuntimeIdentity identity) {
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            Object ackData = handleCommand(request, sessionId, identity);
            emitter.send(SseEmitter.event().name("ack").data(ackData));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private Object handleCommand(ChatStreamRequest request, String sessionId, RuntimeIdentity identity) {
        String namespace = identity.namespace();
        return switch (request.command()) {
            case "steer" -> {
                boolean steered = agentRunRuntime.steer(namespace, sessionId, request.prompt());
                yield Map.of("command", "steer", "applied", steered);
            }
            case "abort" -> {
                boolean aborted = agentRunRuntime.abort(namespace, sessionId, "abort_command");
                yield Map.of("command", "abort", "applied", aborted);
            }
            case "compact" -> {
                sessionRuntime.compact(sessionId, namespace, keepFromArgs(request.commandArgs()));
                yield Map.of("command", "compact");
            }
            case "fork" -> {
                String forkId = sessionRuntime.forkSession(sessionId, namespace, entryIdFromArgs(request.commandArgs()), null);
                yield Map.of("command", "fork", "forkSessionId", forkId);
            }
            case "navigate" -> {
                sessionRuntime.navigateTree(sessionId, namespace, entryIdFromArgs(request.commandArgs()));
                yield Map.of("command", "navigate");
            }
            case "set_model" -> {
                sessionRuntime.setModel(sessionId, namespace, providerFromArgs(request.commandArgs()), modelIdFromArgs(request.commandArgs()));
                yield Map.of("command", "set_model");
            }
            case "set_thinking" -> {
                sessionRuntime.setThinkingLevel(sessionId, namespace, ThinkingLevel.valueOf(levelFromArgs(request.commandArgs())));
                yield Map.of("command", "set_thinking");
            }
            case "continue" -> {
                sessionRuntime.cont(sessionId, namespace);
                yield Map.of("command", "continue");
            }
            default -> throw new IllegalArgumentException("Unknown command: " + request.command());
        };
    }

    private String ensureSessionId(ChatStreamRequest request, String namespace) {
        String sessionId = request.sessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }

        String provider = request.provider();
        String modelId = request.modelId();
        if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()) {
            Model defaultModel = modelCatalog.getAll().isEmpty() ? null : modelCatalog.getAll().get(0);
            if (defaultModel != null) {
                if (provider == null || provider.isBlank()) {
                    provider = defaultModel.provider();
                }
                if (modelId == null || modelId.isBlank()) {
                    modelId = defaultModel.id();
                }
            }
        }
        return sessionRuntime.createSession(new CreateSessionCommand(
                namespace,
                request.projectKey(),
                null,
                provider,
                modelId,
                request.systemPrompt()
        ));
    }

    private void sendRuntimeEvent(SseEmitter emitter, RuntimeEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(toSseData(event)));
            if ("run_completed".equals(event.name())
                    || "run_failed".equals(event.name())
                    || "quota_rejected".equals(event.name())) {
                emitter.complete();
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private Map<String, Object> toSseData(RuntimeEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.eventId());
        payload.put("name", event.name());
        payload.put("runId", event.runId());
        payload.put("tenantId", event.tenantId());
        payload.put("namespace", event.namespace());
        payload.put("userId", event.userId());
        payload.put("sessionId", event.sessionId());
        payload.put("subagentId", event.subagentId());
        payload.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        payload.put("data", event.payload() == null ? Map.of() : event.payload());
        return payload;
    }

    private RunQueueMode parseQueueMode(String queueMode) {
        if (queueMode == null || queueMode.isBlank()) {
            return RunQueueMode.INTERRUPT;
        }
        try {
            return RunQueueMode.valueOf(queueMode.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return RunQueueMode.INTERRUPT;
        }
    }

    private Integer keepFromArgs(Map<String, Object> args) {
        return args == null ? null : (Integer) args.get("keep");
    }

    private String entryIdFromArgs(Map<String, Object> args) {
        return args == null ? null : (String) args.get("entryId");
    }

    private String providerFromArgs(Map<String, Object> args) {
        return args == null ? null : (String) args.get("provider");
    }

    private String modelIdFromArgs(Map<String, Object> args) {
        return args == null ? null : (String) args.get("modelId");
    }

    private String levelFromArgs(Map<String, Object> args) {
        return args == null ? "OFF" : (String) args.getOrDefault("level", "OFF");
    }
}
