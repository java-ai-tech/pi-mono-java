package com.glmapper.coding.http.api.controller;

import com.glmapper.agent.core.*;
import com.glmapper.ai.api.*;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.tools.SkillAgentTool;
import com.glmapper.coding.core.tools.TaskPlanningTool;
import com.glmapper.coding.http.api.dto.StreamChatRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class StreamChatController {
    private final AiRuntime aiRuntime;
    private final ModelCatalog modelCatalog;
    private final SkillsResolver skillsResolver;
    private final ExecutionBackend executionBackend;

    public StreamChatController(AiRuntime aiRuntime, ModelCatalog modelCatalog,
                                SkillsResolver skillsResolver, ExecutionBackend executionBackend) {
        this.aiRuntime = aiRuntime;
        this.modelCatalog = modelCatalog;
        this.skillsResolver = skillsResolver;
        this.executionBackend = executionBackend;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody StreamChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        try {
            Model model = modelCatalog.get(request.provider(), request.modelId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Model not found: " + request.provider() + "/" + request.modelId()));

            Agent agent = new Agent(aiRuntime, model, AgentOptions.defaults());
            agent.state().systemPrompt(request.systemPrompt() != null ? request.systemPrompt() : "");

            // Build execution context for skill tools
            String conversationId = request.sessionId() != null && !request.sessionId().isBlank()
                    ? request.sessionId()
                    : "chat-" + System.currentTimeMillis();
            ExecutionContext execCtx = new ExecutionContext(request.namespace(), conversationId, null);

            // Resolve skills for the namespace (public + namespace-specific)
            List<AgentTool> tools = new ArrayList<>();
            tools.add(new TaskPlanningTool());

            // Convert namespace-visible skills into executable tools
            List<SkillInfo> visibleSkills = skillsResolver.resolveSkills(request.namespace());
            for (SkillInfo skill : visibleSkills) {
                tools.add(new SkillAgentTool(skill, executionBackend, execCtx));
            }

            agent.state().tools(tools);

            // Track last emitted text length per turn to compute delta
            final int[] lastTextLen = {0};

            agent.subscribe(event -> {
                try {
                    if (event instanceof AgentEvent.AgentStart) {
                        emitter.send(SseEmitter.event().name("agent_start").data(Map.of("type", "agent_start")));
                    } else if (event instanceof AgentEvent.TurnStart) {
                        emitter.send(SseEmitter.event().name("turn_start").data(Map.of("type", "turn_start")));
                    } else if (event instanceof AgentEvent.MessageStart start) {
                        String role = start.message().role();
                        if ("assistant".equals(role)) {
                            lastTextLen[0] = 0; // reset for new assistant message
                        }
                        emitter.send(SseEmitter.event().name("message_start")
                                .data(Map.of("type", "message_start", "role", role)));
                    } else if (event instanceof AgentEvent.MessageUpdate update) {
                        if (update.message() instanceof AgentAssistantMessage assistant) {
                            String fullText = extractText(assistant.content());
                            if (fullText.length() > lastTextLen[0]) {
                                String delta = fullText.substring(lastTextLen[0]);
                                lastTextLen[0] = fullText.length();
                                emitter.send(SseEmitter.event().name("text")
                                        .data(Map.of("type", "text", "delta", delta)));
                            }
                        }
                    } else if (event instanceof AgentEvent.MessageEnd end) {
                        if (end.message() instanceof AgentAssistantMessage assistant) {
                            String text = extractText(assistant.content());
                            emitter.send(SseEmitter.event().name("message_end")
                                    .data(Map.of("type", "message_end", "role", "assistant", "text", text)));
                        }
                    } else if (event instanceof AgentEvent.ToolExecutionStart toolStart) {
                        emitter.send(SseEmitter.event().name("tool_start")
                                .data(Map.of("type", "tool_start", "toolName", toolStart.toolName(),
                                        "toolCallId", toolStart.toolCallId())));
                    } else if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
                        String resultText = extractText(toolEnd.result().content());
                        emitter.send(SseEmitter.event().name("tool_end")
                                .data(Map.of("type", "tool_end", "toolName", toolEnd.toolName(),
                                        "toolCallId", toolEnd.toolCallId(),
                                        "result", resultText != null ? resultText : "",
                                        "isError", toolEnd.isError())));
                    } else if (event instanceof AgentEvent.TurnEnd) {
                        emitter.send(SseEmitter.event().name("turn_end").data(Map.of("type", "turn_end")));
                    } else if (event instanceof AgentEvent.AgentEnd) {
                        emitter.send(SseEmitter.event().name("done").data(Map.of("type", "done")));
                        emitter.complete();
                    }
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });

            agent.prompt(request.prompt()).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("type", "error", "message", throwable.getMessage())));
                    } catch (IOException ignored) {
                    }
                    emitter.completeWithError(throwable);
                }
            });
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("type", "error", "message", "初始化失败: " + e.getMessage())));
            } catch (IOException ignored) {
            }
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private String extractText(List<ContentBlock> content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextContent text) {
                if (text.text() != null) {
                    sb.append(text.text());
                }
            }
        }
        return sb.toString();
    }
}
