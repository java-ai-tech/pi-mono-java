package com.glmapper.coding.http.api.controller;

import com.glmapper.agent.core.AgentEvent;
import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.rpc.RpcCommandProcessor;
import com.glmapper.coding.core.rpc.RpcCommandRequest;
import com.glmapper.coding.core.rpc.RpcCommandResponse;
import com.glmapper.coding.sdk.DelphiCodingAgentSdk;
import com.glmapper.coding.http.api.config.SessionEventBroker;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rpc")
public class RpcController {
    private final RpcCommandProcessor rpcCommandProcessor;
    private final SessionEventBroker eventBroker;
    private final DelphiCodingAgentSdk sdk;
    private final ResourceCatalogService resourceCatalogService;
    private final SkillsResolver skillsResolver;

    public RpcController(
            RpcCommandProcessor rpcCommandProcessor,
            SessionEventBroker eventBroker,
            DelphiCodingAgentSdk sdk,
            ResourceCatalogService resourceCatalogService,
            SkillsResolver skillsResolver
    ) {
        this.rpcCommandProcessor = rpcCommandProcessor;
        this.eventBroker = eventBroker;
        this.sdk = sdk;
        this.resourceCatalogService = resourceCatalogService;
        this.skillsResolver = skillsResolver;
    }

    @PostMapping("/command")
    public RpcCommandResponse command(@RequestBody RpcCommandRequest request) {
        return rpcCommandProcessor.execute(request);
    }

    @GetMapping(value = "/events/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String sessionId, @RequestParam String namespace) {
        SseEmitter emitter = eventBroker.register(sessionId);
        AutoCloseable subscription = sdk.subscribeEvents(sessionId, namespace, event ->
                eventBroker.publish(sessionId, "rpc_event", mapAgentEvent(event)));

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

    @GetMapping("/catalog/skills")
    public Object skills(@RequestParam String namespace) {
        return skillsResolver.resolveSkills(namespace);
    }

    @GetMapping("/catalog/prompts")
    public Object prompts() {
        return resourceCatalogService.prompts();
    }

    @GetMapping("/catalog/resources")
    public Object resources() {
        return resourceCatalogService.resources();
    }

    @PostMapping("/catalog/reload")
    public Map<String, Object> reloadCatalog() {
        resourceCatalogService.reload();
        skillsResolver.invalidateCache(null);
        return Map.of(
                "skills", resourceCatalogService.skills().size(),
                "prompts", resourceCatalogService.prompts().size(),
                "resources", resourceCatalogService.resources().size()
        );
    }

    private Map<String, Object> mapAgentEvent(AgentEvent event) {
        Map<String, Object> payload = new HashMap<>();
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
