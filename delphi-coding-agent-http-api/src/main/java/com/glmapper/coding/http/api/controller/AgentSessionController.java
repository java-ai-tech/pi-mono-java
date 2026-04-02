package com.glmapper.coding.http.api.controller;

import com.glmapper.agent.core.AgentEvent;
import com.glmapper.ai.api.ThinkingLevel;
import com.glmapper.coding.http.api.config.SessionEventBroker;
import com.glmapper.coding.http.api.dto.*;
import com.glmapper.coding.sdk.CreateAgentSessionOptions;
import com.glmapper.coding.sdk.DelphiCodingAgentSdk;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class AgentSessionController {
    private final DelphiCodingAgentSdk sdk;
    private final SessionEventBroker eventBroker;

    public AgentSessionController(DelphiCodingAgentSdk sdk, SessionEventBroker eventBroker) {
        this.sdk = sdk;
        this.eventBroker = eventBroker;
    }

    @PostMapping
    public CreateSessionResponse create(@Valid @RequestBody CreateSessionRequest request) {
        var handle = sdk.createSession(new CreateAgentSessionOptions(
                request.namespace(),
                request.projectKey(),
                request.sessionName(),
                request.provider(),
                request.modelId(),
                request.systemPrompt()
        ));
        eventBroker.publish(handle.sessionId(), "session_created", sdk.state(handle.sessionId(), request.namespace()));
        return new CreateSessionResponse(handle.sessionId());
    }

    @GetMapping
    public List<?> list(@RequestParam String namespace, @RequestParam String projectKey) {
        return sdk.sessions(namespace, projectKey);
    }

    @GetMapping("/models")
    public List<?> models(@RequestParam(required = false) String provider) {
        if (provider == null || provider.isBlank()) {
            return sdk.availableModels();
        }
        return sdk.availableModels(provider);
    }

    @GetMapping("/{sessionId}/state")
    public Object state(@PathVariable String sessionId, @RequestParam String namespace) {
        return sdk.state(sessionId, namespace);
    }

    @GetMapping("/{sessionId}/messages")
    public Object messages(@PathVariable String sessionId, @RequestParam String namespace) {
        return sdk.messages(sessionId, namespace);
    }

    @GetMapping("/{sessionId}/tree")
    public Object tree(@PathVariable String sessionId, @RequestParam String namespace) {
        return sdk.tree(sessionId, namespace);
    }

    @PostMapping("/{sessionId}/model")
    public OperationAcceptedResponse setModel(@PathVariable String sessionId, @RequestParam String namespace,
                                              @Valid @RequestBody SetModelRequest request) {
        sdk.setModel(sessionId, namespace, request.provider(), request.modelId());
        eventBroker.publish(sessionId, "model_changed", Map.of("provider", request.provider(), "modelId", request.modelId()));
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/thinking-level")
    public OperationAcceptedResponse setThinkingLevel(@PathVariable String sessionId, @RequestParam String namespace,
                                                      @Valid @RequestBody SetThinkingLevelRequest request) {
        ThinkingLevel level = ThinkingLevel.valueOf(request.level().toUpperCase());
        sdk.setThinkingLevel(sessionId, namespace, level);
        eventBroker.publish(sessionId, "thinking_level_changed", Map.of("level", level.name()));
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/fork")
    public ForkResponse fork(@PathVariable String sessionId, @RequestParam String namespace,
                             @RequestBody(required = false) ForkRequest request) {
        String forkSessionId = sdk.fork(
                sessionId,
                namespace,
                request == null ? null : request.entryId(),
                request == null ? null : request.sessionName()
        );
        eventBroker.publish(sessionId, "fork_created", Map.of("forkSessionId", forkSessionId));
        return new ForkResponse(forkSessionId);
    }

    @PostMapping("/{sessionId}/navigate")
    public OperationAcceptedResponse navigate(@PathVariable String sessionId, @RequestParam String namespace,
                                              @Valid @RequestBody NavigateTreeRequest request) {
        sdk.navigateTree(sessionId, namespace, request.entryId());
        eventBroker.publish(sessionId, "tree_navigated", Map.of("entryId", request.entryId()));
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/prompt")
    public OperationAcceptedResponse prompt(@PathVariable String sessionId, @RequestParam String namespace,
                                            @Valid @RequestBody PromptRequest request) {
        eventBroker.publish(sessionId, "prompt_received", request);
        sdk.prompt(sessionId, namespace, request.message())
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        eventBroker.publish(sessionId, "prompt_failed", Map.of("error", throwable.getMessage()));
                    } else {
                        eventBroker.publish(sessionId, "prompt_completed", sdk.state(sessionId, namespace));
                    }
                });
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/continue")
    public OperationAcceptedResponse cont(@PathVariable String sessionId, @RequestParam String namespace) {
        eventBroker.publish(sessionId, "continue_requested", Map.of("sessionId", sessionId));
        sdk.cont(sessionId, namespace)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        eventBroker.publish(sessionId, "continue_failed", Map.of("error", throwable.getMessage()));
                    } else {
                        eventBroker.publish(sessionId, "continue_completed", sdk.state(sessionId, namespace));
                    }
                });
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/steer")
    public OperationAcceptedResponse steer(@PathVariable String sessionId, @RequestParam String namespace,
                                           @Valid @RequestBody PromptRequest request) {
        sdk.steer(sessionId, namespace, request.message());
        eventBroker.publish(sessionId, "steer_queued", Map.of("message", request.message()));
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/follow-up")
    public OperationAcceptedResponse followUp(@PathVariable String sessionId, @RequestParam String namespace,
                                              @Valid @RequestBody PromptRequest request) {
        sdk.followUp(sessionId, namespace, request.message());
        eventBroker.publish(sessionId, "follow_up_queued", Map.of("message", request.message()));
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/compact")
    public OperationAcceptedResponse compact(@PathVariable String sessionId, @RequestParam String namespace,
                                             @RequestBody(required = false) CompactRequest request) {
        Integer keep = request == null ? null : request.keepRecentMessages();
        int effectiveKeep = keep == null ? 20 : Math.max(1, keep);
        sdk.compact(sessionId, namespace, keep);
        eventBroker.publish(sessionId, "context_compacted", Map.of("keepRecentMessages", effectiveKeep));
        return new OperationAcceptedResponse("accepted");
    }

    @PostMapping("/{sessionId}/abort")
    public OperationAcceptedResponse abort(@PathVariable String sessionId, @RequestParam String namespace) {
        sdk.abort(sessionId, namespace);
        eventBroker.publish(sessionId, "abort_requested", Map.of("sessionId", sessionId));
        return new OperationAcceptedResponse("accepted");
    }

    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String sessionId, @RequestParam String namespace) {
        SseEmitter emitter = eventBroker.register(sessionId);
        AutoCloseable subscription = sdk.subscribeEvents(sessionId, namespace, event ->
                eventBroker.publish(sessionId, "agent_event", mapAgentEvent(event)));

        Runnable cleanup = () -> {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        eventBroker.publish(sessionId, "connected", Map.of("sessionId", sessionId));
        return emitter;
    }

    private Map<String, Object> mapAgentEvent(AgentEvent event) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", event.getClass().getSimpleName());

        if (event instanceof AgentEvent.MessageStart messageStart) {
            payload.put("role", messageStart.message().role());
        } else if (event instanceof AgentEvent.MessageEnd messageEnd) {
            payload.put("role", messageEnd.message().role());
        } else if (event instanceof AgentEvent.ToolExecutionStart toolExecutionStart) {
            payload.put("toolCallId", toolExecutionStart.toolCallId());
            payload.put("toolName", toolExecutionStart.toolName());
        } else if (event instanceof AgentEvent.ToolExecutionEnd toolExecutionEnd) {
            payload.put("toolCallId", toolExecutionEnd.toolCallId());
            payload.put("toolName", toolExecutionEnd.toolName());
            payload.put("isError", toolExecutionEnd.isError());
        }

        return payload;
    }
}
