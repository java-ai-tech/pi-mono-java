package com.glmapper.coding.http.api.config;

import com.glmapper.coding.core.cluster.ClusterNodeIdentity;
import com.glmapper.coding.core.runtime.RuntimeEvent;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SessionEventBroker implements RuntimeEventSink {
    private static final Logger log = LoggerFactory.getLogger(SessionEventBroker.class);
    private static final Set<String> TERMINAL_EVENTS = Set.of("run_completed", "run_failed", "quota_rejected");

    private final Map<String, Set<EmitterEntry>> bySessionKey = new ConcurrentHashMap<>();
    private final ClusterNodeIdentity nodeIdentity;
    private final ObjectProvider<RedisSseEventPublisher> redisPublisher;

    public SessionEventBroker(ClusterNodeIdentity nodeIdentity,
                              ObjectProvider<RedisSseEventPublisher> redisPublisher) {
        this.nodeIdentity = nodeIdentity;
        this.redisPublisher = redisPublisher;
    }

    public SseEmitter register(String namespace, String sessionId, String runId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        EmitterEntry entry = new EmitterEntry(emitter, namespace, sessionId, runId);
        String key = sessionKey(namespace, sessionId);

        bySessionKey.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(entry);

        emitter.onCompletion(() -> remove(key, entry));
        emitter.onTimeout(() -> {
            remove(key, entry);
            emitter.complete();
        });
        emitter.onError(err -> remove(key, entry));
        return emitter;
    }

    public SseEmitter registerCommand(String namespace, String sessionId, long timeoutMs) {
        return register(namespace, sessionId, null, timeoutMs);
    }

    @Override
    public void emit(RuntimeEvent event) {
        publish(event);
    }

    public void publish(RuntimeEvent event) {
        sendLocal(event);
        RedisSseEventPublisher publisher = redisPublisher.getIfAvailable();
        if (publisher != null) {
            publisher.publish(new SseEventEnvelope(nodeIdentity.getNodeId(), event));
        }
    }

    public void sendLocal(RuntimeEvent event) {
        if (event == null || event.sessionId() == null || event.namespace() == null) {
            return;
        }
        String key = sessionKey(event.namespace(), event.sessionId());
        Set<EmitterEntry> entries = bySessionKey.get(key);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        boolean terminal = TERMINAL_EVENTS.contains(event.name());
        for (EmitterEntry entry : entries) {
            if (entry.runId != null && event.runId() != null && !entry.runId.equals(event.runId())) {
                continue;
            }
            try {
                entry.emitter.send(SseEmitter.event().name(event.name()).data(toSseData(event)));
                if (terminal && entry.runId != null && entry.runId.equals(event.runId())) {
                    entry.emitter.complete();
                }
            } catch (IOException e) {
                log.debug("Failed to send event to emitter, removing", e);
                remove(key, entry);
            } catch (IllegalStateException e) {
                remove(key, entry);
            }
        }
    }

    int activeEmitterCount(String namespace, String sessionId) {
        Set<EmitterEntry> entries = bySessionKey.get(sessionKey(namespace, sessionId));
        return entries == null ? 0 : entries.size();
    }

    private void remove(String key, EmitterEntry entry) {
        Set<EmitterEntry> entries = bySessionKey.get(key);
        if (entries == null) {
            return;
        }
        entries.remove(entry);
        if (entries.isEmpty()) {
            bySessionKey.remove(key);
        }
    }

    private String sessionKey(String namespace, String sessionId) {
        return namespace + "::" + sessionId;
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

    private static final class EmitterEntry {
        private final SseEmitter emitter;
        private final String namespace;
        private final String sessionId;
        private final String runId;

        EmitterEntry(SseEmitter emitter, String namespace, String sessionId, String runId) {
            this.emitter = emitter;
            this.namespace = namespace;
            this.sessionId = sessionId;
            this.runId = runId;
        }
    }
}
