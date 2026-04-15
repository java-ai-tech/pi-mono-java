package com.glmapper.coding.http.api.controller;

import com.glmapper.agent.core.AgentEvent;
import com.glmapper.ai.api.ThinkingLevel;
import com.glmapper.coding.core.domain.CreateSessionCommand;
import com.glmapper.coding.core.orchestration.OrchestratedChatService;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import com.glmapper.coding.http.api.config.SessionEventBroker;
import com.glmapper.coding.http.api.dto.ChatStreamRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import com.glmapper.ai.api.AssistantMessageEvent;
import com.glmapper.ai.api.TextContent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/chat")
public class StreamChatController {
    private final AgentSessionRuntime runtime;
    private final OrchestratedChatService orchestratedChatService;
    private final SessionEventBroker eventBroker;

    public StreamChatController(AgentSessionRuntime runtime,
                                OrchestratedChatService orchestratedChatService,
                                SessionEventBroker eventBroker) {
        this.runtime = runtime;
        this.orchestratedChatService = orchestratedChatService;
        this.eventBroker = eventBroker;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatStreamRequest request) {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            String provider = request.provider() != null ? request.provider() : "anthropic";
            String modelId = request.modelId() != null ? request.modelId() : "claude-opus-4-6";
            sessionId = runtime.createSession(new CreateSessionCommand(
                    request.namespace(), request.projectKey(), null,
                    provider, modelId, request.systemPrompt()));
            eventBroker.publish(sessionId, "session_created", Map.of("sessionId", sessionId));
        }

        String finalSessionId = sessionId;

        // Command routing
        if (request.command() != null) {
            SseEmitter emitter = new SseEmitter(30_000L);
            try {
                Object ackData = handleCommand(request, finalSessionId);
                emitter.send(SseEmitter.event().name("ack").data(ackData));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // Prompt mode
        if ("orchestrated".equals(request.mode())) {
            SseEmitter emitter = new SseEmitter(300_000L);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                orchestratedChatService.stream(
                        request.namespace(), request.prompt(),
                        request.provider(), request.modelId(),
                        request.systemPrompt(), finalSessionId,
                        (eventName, data) -> {
                            try { emitter.send(SseEmitter.event().name(eventName).data(data)); }
                            catch (IOException e) { emitter.completeWithError(e); }
                        });
                emitter.complete();
            }).exceptionally(ex -> { emitter.completeWithError(ex); return null; });
            return emitter;
        }

        // Agent mode (default) — write directly to emitter with frontend-compatible event names
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicInteger lastTextLen = new AtomicInteger(0);

        AutoCloseable subscription = runtime.subscribeEvents(finalSessionId, request.namespace(), event -> {
            try {
                if (event instanceof AgentEvent.AgentStart) {
                    emitter.send(SseEmitter.event().name("agent_start").data(Map.of("type", "agent_start")));
                } else if (event instanceof AgentEvent.TurnStart) {
                    emitter.send(SseEmitter.event().name("turn_start").data(Map.of("type", "turn_start")));
                } else if (event instanceof AgentEvent.MessageStart ms) {
                    lastTextLen.set(0);
                    emitter.send(SseEmitter.event().name("message_start")
                            .data(Map.of("type", "message_start", "role", ms.message().role())));
                } else if (event instanceof AgentEvent.MessageUpdate mu) {
                    if (mu.message() instanceof com.glmapper.agent.core.AgentAssistantMessage assistant) {
                        String fullText = extractText(assistant.content());
                        int prev = lastTextLen.get();
                        if (fullText.length() > prev) {
                            String delta = fullText.substring(prev);
                            lastTextLen.set(fullText.length());
                            emitter.send(SseEmitter.event().name("text")
                                    .data(Map.of("type", "text", "delta", delta)));
                        }
                    }
                } else if (event instanceof AgentEvent.MessageEnd me) {
                    emitter.send(SseEmitter.event().name("message_end")
                            .data(Map.of("type", "message_end", "role", me.message().role())));
                } else if (event instanceof AgentEvent.ToolExecutionStart ts) {
                    emitter.send(SseEmitter.event().name("tool_start")
                            .data(Map.of("type", "tool_start", "toolName", ts.toolName(), "toolCallId", ts.toolCallId())));
                } else if (event instanceof AgentEvent.ToolExecutionEnd te) {
                    String result = extractText(te.result().content());
                    emitter.send(SseEmitter.event().name("tool_end")
                            .data(Map.of("type", "tool_end", "toolName", te.toolName(),
                                    "toolCallId", te.toolCallId(), "result", result != null ? result : "",
                                    "isError", te.isError())));
                } else if (event instanceof AgentEvent.TurnEnd) {
                    emitter.send(SseEmitter.event().name("turn_end").data(Map.of("type", "turn_end")));
                } else if (event instanceof AgentEvent.AgentEnd) {
                    emitter.send(SseEmitter.event().name("done").data(Map.of("type", "done")));
                    emitter.complete();
                }
            } catch (IOException ignored) {}
        });

        Runnable cleanup = () -> { try { subscription.close(); } catch (Exception ignored) {} };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        runtime.prompt(finalSessionId, request.namespace(), request.prompt())
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        try {
                            emitter.send(SseEmitter.event().name("error")
                                    .data(Map.of("type", "error", "message", ex.getMessage())));
                            emitter.complete();
                        } catch (Exception ignored) {}
                    }
                });
        return emitter;
    }

    private Object handleCommand(ChatStreamRequest request, String sessionId) {
        String ns = request.namespace();
        return switch (request.command()) {
            case "steer" -> {
                runtime.steer(sessionId, ns, request.prompt());
                yield Map.of("command", "steer");
            }
            case "abort" -> {
                runtime.abort(sessionId, ns);
                yield Map.of("command", "abort");
            }
            case "compact" -> {
                runtime.compact(sessionId, ns, keepFromArgs(request.commandArgs()));
                yield Map.of("command", "compact");
            }
            case "fork" -> {
                String forkId = runtime.forkSession(sessionId, ns,
                        entryIdFromArgs(request.commandArgs()), null);
                yield Map.of("command", "fork", "forkSessionId", forkId);
            }
            case "navigate" -> {
                runtime.navigateTree(sessionId, ns, entryIdFromArgs(request.commandArgs()));
                yield Map.of("command", "navigate");
            }
            case "set_model" -> {
                runtime.setModel(sessionId, ns,
                        providerFromArgs(request.commandArgs()),
                        modelIdFromArgs(request.commandArgs()));
                yield Map.of("command", "set_model");
            }
            case "set_thinking" -> {
                runtime.setThinkingLevel(sessionId, ns,
                        ThinkingLevel.valueOf(levelFromArgs(request.commandArgs())));
                yield Map.of("command", "set_thinking");
            }
            case "continue" -> {
                runtime.cont(sessionId, ns);
                yield Map.of("command", "continue");
            }
            default -> throw new IllegalArgumentException("Unknown command: " + request.command());
        };
    }

    private Map<String, Object> mapAgentEvent(AgentEvent event) {
        // Kept for potential future use by eventBroker consumers
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.getClass().getSimpleName());
        return payload;
    }

    private String extractText(List<com.glmapper.ai.api.ContentBlock> content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var block : content) {
            if (block instanceof TextContent text && text.text() != null) {
                sb.append(text.text());
            }
        }
        return sb.toString();
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
